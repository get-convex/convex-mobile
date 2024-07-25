package dev.convex.android

import java.lang.Exception

class ConvexException(message: String, cause: Exception) : Exception(message, cause) {
    companion object {
        fun from(exception: ClientException): ConvexException =
            ConvexException(exception.message(), exception)
    }
}