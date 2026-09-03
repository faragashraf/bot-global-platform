package com.botglobal.mobile.platform.networking

import kotlin.jvm.JvmInline

sealed interface ApiResult<out Value> {
    data class Success<Value>(
        val value: Value,
        val statusCode: Int,
    ) : ApiResult<Value> {
        override fun toString(): String =
            "ApiResult.Success(statusCode=$statusCode,value=<redacted>)"
    }

    data class Failure(
        val error: ApiError,
    ) : ApiResult<Nothing> {
        override fun toString(): String = "ApiResult.Failure(error=$error)"
    }
}

enum class ApiErrorKind {
    Transport,
    Timeout,
    Unauthorized,
    Forbidden,
    NotFound,
    Conflict,
    Validation,
    Server,
    Unavailable,
    Unknown,
}

data class ApiError(
    val kind: ApiErrorKind,
    val statusCode: Int? = null,
) {
    override fun toString(): String = "ApiError(kind=$kind,statusCode=${statusCode ?: "none"})"
}

enum class TransportFailureKind {
    NetworkUnavailable,
    Connection,
    Timeout,
    Unknown,
}

fun apiErrorFromHttpStatus(statusCode: Int): ApiError? = when (statusCode) {
    in 200..299 -> null
    400, 422 -> ApiError(ApiErrorKind.Validation, statusCode)
    401 -> ApiError(ApiErrorKind.Unauthorized, statusCode)
    403 -> ApiError(ApiErrorKind.Forbidden, statusCode)
    404 -> ApiError(ApiErrorKind.NotFound, statusCode)
    408, 504 -> ApiError(ApiErrorKind.Timeout, statusCode)
    409 -> ApiError(ApiErrorKind.Conflict, statusCode)
    429, 502, 503 -> ApiError(ApiErrorKind.Unavailable, statusCode)
    in 500..599 -> ApiError(ApiErrorKind.Server, statusCode)
    else -> ApiError(ApiErrorKind.Unknown, statusCode)
}

fun apiErrorFromTransport(failure: TransportFailureKind): ApiError = ApiError(
    when (failure) {
        TransportFailureKind.NetworkUnavailable,
        TransportFailureKind.Connection,
        -> ApiErrorKind.Transport

        TransportFailureKind.Timeout -> ApiErrorKind.Timeout
        TransportFailureKind.Unknown -> ApiErrorKind.Unknown
    },
)

@JvmInline
value class NetworkOperationId(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9._-]{0,63}"))) {
            "Network operation IDs must be stable non-sensitive identifiers."
        }
    }

    override fun toString(): String = value
}

data class NetworkDiagnostic(
    val operation: NetworkOperationId,
    val error: ApiError,
) {
    override fun toString(): String = "NetworkDiagnostic(operation=$operation,error=$error)"
}
