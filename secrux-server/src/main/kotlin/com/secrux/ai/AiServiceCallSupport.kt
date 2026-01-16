package com.secrux.ai

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import org.slf4j.Logger
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException

internal class AiServiceCallSupport(
    private val log: Logger
) {

    fun <T : Any> blockingCall(
        operation: String,
        supplier: () -> T?
    ): T {
        return try {
            supplier()
                ?: throw SecruxException(
                    errorCode = ErrorCode.AI_SERVICE_UNAVAILABLE,
                    message = "AI service returned empty response ($operation)",
                    retryable = true,
                    context = mapOf("operation" to operation)
                )
        } catch (ex: WebClientResponseException) {
            val status = ex.statusCode.value()
            val body = ex.responseBodyAsString.take(2000)
            val retryable = status == 429 || status >= 500
            log.warn("event=ai_service_request_failed operation={} status={} retryable={} body={}", operation, status, retryable, body)
            throw SecruxException(
                errorCode = ErrorCode.AI_SERVICE_UNAVAILABLE,
                message = "AI service request failed ($operation, status=$status): $body",
                retryable = retryable,
                context = mapOf(
                    "operation" to operation,
                    "status" to status,
                    "body" to body.take(500)
                )
            )
        } catch (ex: WebClientRequestException) {
            log.warn("event=ai_service_request_error operation={} error={}", operation, ex.message)
            throw SecruxException(
                errorCode = ErrorCode.AI_SERVICE_UNAVAILABLE,
                message = "AI service request error ($operation): ${ex.message}",
                retryable = true,
                context = mapOf("operation" to operation)
            )
        } catch (ex: SecruxException) {
            throw ex
        } catch (ex: Exception) {
            log.warn("event=ai_service_client_error operation={} error={}", operation, ex.message)
            throw SecruxException(
                errorCode = ErrorCode.INTERNAL_ERROR,
                message = "AI service client error ($operation): ${ex.message}",
                retryable = false,
                context = mapOf("operation" to operation)
            )
        }
    }

    fun blockingUnitCall(operation: String, supplier: () -> Any?) {
        blockingCall(operation, supplier)
    }
}

