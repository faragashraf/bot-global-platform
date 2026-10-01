package com.botglobal.mobile.platform.networking

/** Reject paths whose meaning can change after URL decoding or normalization. */
internal fun hasUnambiguousUrlPath(path: String): Boolean =
    '%' !in path && '\\' !in path && path.split('/').none { it == "." || it == ".." }
