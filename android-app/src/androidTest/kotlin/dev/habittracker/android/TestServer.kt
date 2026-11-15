package dev.habittracker.android

import androidx.test.platform.app.InstrumentationRegistry

object TestServer { val url: String get()=InstrumentationRegistry.getArguments().getString("backend_url") ?: "http://10.0.2.2:8081" }
