package dev.habittracker.backend

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.core.sync.RequestBody
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Private blob storage used by authenticated attachment endpoints. Keys are UUIDs, never user filenames. */
interface AttachmentStorage : AutoCloseable {
    fun put(key: String, bytes: ByteArray)
    fun get(key: String): ByteArray?
    /** Returns true when the key is absent or was deleted; false means cleanup should be retried. */
    fun delete(key: String): Boolean
    fun check(): Boolean
    override fun close() = Unit
}

class LocalFilesystemAttachmentStorage(private val directory: Path) : AttachmentStorage {
    init { Files.createDirectories(directory) }

    override fun put(key: String, bytes: ByteArray) {
        Files.write(path(key), bytes)
    }

    override fun get(key: String): ByteArray? {
        val file = path(key)
        return if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
    }

    override fun delete(key: String): Boolean = runCatching {
        Files.deleteIfExists(path(key))
        true
    }.getOrDefault(false)

    override fun check(): Boolean = Files.isDirectory(directory) && Files.isWritable(directory)

    private fun path(key: String): Path = directory.resolve(validKey(key))
}

data class S3StorageConfig(
    val endpoint: String,
    val bucket: String,
    val accessKey: String,
    val secretKey: String,
    val region: String = "auto",
) {
    init {
        val uri = runCatching { URI(endpoint) }.getOrElse { throw IllegalArgumentException("Invalid S3_ENDPOINT") }
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Invalid S3_ENDPOINT"
        }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "S3_ENDPOINT must not include a path" }
        require(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]").matches(bucket)) { "Invalid S3_BUCKET" }
        require(accessKey.isNotBlank() && secretKey.isNotBlank()) { "S3 credentials must not be blank" }
        require(region.isNotBlank() && region.length <= 64) { "Invalid S3_REGION" }
    }

    override fun toString(): String =
        "S3StorageConfig(endpoint=$endpoint, bucket=$bucket, accessKey=[redacted], secretKey=[redacted], region=$region)"
}

/** Small injectable boundary so storage behavior can be tested without network credentials or a bucket. */
interface S3ObjectClient : AutoCloseable {
    fun put(bucket: String, key: String, bytes: ByteArray)
    fun get(bucket: String, key: String): ByteArray?
    fun delete(bucket: String, key: String)
    fun checkBucket(bucket: String): Boolean
    override fun close() = Unit
}

class S3AttachmentStorage(
    private val config: S3StorageConfig,
    private val client: S3ObjectClient = AwsS3ObjectClient(config),
) : AttachmentStorage {
    override fun put(key: String, bytes: ByteArray) = client.put(config.bucket, validKey(key), bytes)
    override fun get(key: String): ByteArray? = client.get(config.bucket, validKey(key))
    override fun delete(key: String): Boolean = runCatching {
        client.delete(config.bucket, validKey(key))
        true
    }.getOrDefault(false)
    override fun check(): Boolean = runCatching { client.checkBucket(config.bucket) }.getOrDefault(false)
    override fun close() = client.close()
}

private class AwsS3ObjectClient(config: S3StorageConfig) : S3ObjectClient {
    private val s3 = S3Client.builder()
        .endpointOverride(URI(config.endpoint))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKey, config.secretKey)))
        .region(Region.of(config.region))
        .serviceConfiguration(S3Configuration.builder()
            .pathStyleAccessEnabled(true)
            .chunkedEncodingEnabled(false)
            .build())
        .build()

    override fun put(bucket: String, key: String, bytes: ByteArray) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(bytes))
    }

    override fun get(bucket: String, key: String): ByteArray? = try {
        s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray()
    } catch (e: S3Exception) {
        if (e.statusCode() == 404 || e.awsErrorDetails()?.errorCode() in setOf("NoSuchKey", "NotFound")) null else throw e
    }

    override fun delete(bucket: String, key: String) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
    }

    override fun checkBucket(bucket: String): Boolean {
        s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
        return true
    }

    override fun close() = s3.close()
}

private fun validKey(key: String): String {
    val uuid = runCatching { UUID.fromString(key) }.getOrElse { throw IllegalArgumentException("Invalid attachment key") }
    require(uuid.toString() == key) { "Invalid attachment key" }
    return key
}
