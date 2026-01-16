package com.secrux.common

import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import jakarta.validation.ConstraintViolationException
import org.jooq.exception.IntegrityConstraintViolationException

@RestControllerAdvice
class GlobalExceptionHandler(
    private val messageSource: MessageSource
) {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    private val retryableHeader = "X-Secrux-Retryable"

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Nothing>> {
        val details = ex.bindingResult.allErrors.joinToString("; ") { error ->
            if (error is FieldError) {
                "${error.field}: ${error.defaultMessage}"
            } else {
                error.defaultMessage ?: "validation error"
            }
        }
        val message = resolveMessage("error.validation", arrayOf(details))
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse(success = false, code = ErrorCode.VALIDATION_ERROR.name, message = message))
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraint(ex: ConstraintViolationException): ResponseEntity<ApiResponse<Nothing>> {
        val message = resolveMessage("error.validation", arrayOf(ex.message ?: "validation error"))
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse(success = false, code = ErrorCode.VALIDATION_ERROR.name, message = message))
    }

    @ExceptionHandler(SecruxException::class)
    fun handleDomain(ex: SecruxException): ResponseEntity<ApiResponse<Nothing>> {
        val status = resolveHttpStatus(ex)
        val headers =
            HttpHeaders().also { h ->
                if (ex.retryable) {
                    h.add(retryableHeader, "true")
                }
                if (ex.errorCode == ErrorCode.AI_SERVICE_UNAVAILABLE) {
                    val upstreamStatus = (ex.context["status"] as? Number)?.toInt()
                    if (upstreamStatus != null) {
                        h.add("X-Upstream-Status", upstreamStatus.toString())
                    }
                }
            }

        if (status.is5xxServerError) {
            log.warn(
                "event=api_domain_error status={} code={} retryable={} message={} context={}",
                status.value(),
                ex.errorCode.name,
                ex.retryable,
                ex.message,
                ex.context,
                ex
            )
        }
        return ResponseEntity
            .status(status)
            .headers(headers)
            .body(ApiResponse(success = false, code = ex.errorCode.name, message = ex.message))
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ResponseEntity<ApiResponse<Nothing>> {
        val message = ex.reason?.takeIf { it.isNotBlank() } ?: ex.message
        val code =
            when (ex.statusCode) {
                HttpStatus.BAD_REQUEST -> ErrorCode.VALIDATION_ERROR
                HttpStatus.UNAUTHORIZED -> ErrorCode.UNAUTHORIZED
                HttpStatus.FORBIDDEN -> ErrorCode.FORBIDDEN
                else -> ErrorCode.INTERNAL_ERROR
            }
        return ResponseEntity
            .status(ex.statusCode)
            .body(ApiResponse(success = false, code = code.name, message = message))
    }

    @ExceptionHandler(IntegrityConstraintViolationException::class)
    fun handleIntegrity(ex: IntegrityConstraintViolationException): ResponseEntity<ApiResponse<Nothing>> {
        val raw = ex.message.orEmpty()
        val message =
            when {
                raw.contains("task_tenant_id_correlation_id_key") ->
                    resolveMessage("error.task.correlation.duplicate")
                else ->
                    resolveMessage("error.validation", arrayOf("database constraint violation"))
            }
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ApiResponse(success = false, code = ErrorCode.VALIDATION_ERROR.name, message = message))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnknown(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        log.error(
            "event=api_unhandled_exception exceptionClass={} error={}",
            ex.javaClass.simpleName,
            ex.message,
            ex
        )
        val message = resolveMessage("error.internal")
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse(success = false, code = ErrorCode.INTERNAL_ERROR.name, message = message))
    }

    private fun resolveMessage(code: String, args: Array<Any>? = null): String {
        val finalArgs = args ?: emptyArray<Any>()
        return messageSource.getMessage(code, finalArgs, LocaleContextHolder.getLocale())
    }

    private fun resolveHttpStatus(ex: SecruxException): HttpStatus =
        when (ex.errorCode) {
            ErrorCode.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED
            ErrorCode.FORBIDDEN -> HttpStatus.FORBIDDEN
            ErrorCode.VALIDATION_ERROR -> HttpStatus.BAD_REQUEST
            ErrorCode.AUTH_INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED
            ErrorCode.AUTH_TOKEN_INVALID -> HttpStatus.UNAUTHORIZED

            ErrorCode.TASK_NOT_FOUND,
            ErrorCode.PROJECT_NOT_FOUND,
            ErrorCode.REPOSITORY_NOT_FOUND,
            ErrorCode.TICKET_NOT_FOUND,
            ErrorCode.EXECUTOR_NOT_FOUND,
            ErrorCode.UPLOAD_NOT_FOUND,
            ErrorCode.STAGE_NOT_FOUND,
            ErrorCode.FINDING_NOT_FOUND,
            ErrorCode.SCA_ISSUE_NOT_FOUND,
            ErrorCode.BASELINE_NOT_FOUND,
            ErrorCode.RULE_NOT_FOUND,
            ErrorCode.RULE_GROUP_NOT_FOUND,
            ErrorCode.RULESET_NOT_FOUND,
            ErrorCode.USER_NOT_FOUND,
            ErrorCode.ROLE_NOT_FOUND,
            ErrorCode.TENANT_NOT_FOUND -> HttpStatus.NOT_FOUND

            ErrorCode.AI_SERVICE_UNAVAILABLE -> {
                val upstreamStatus = (ex.context["status"] as? Number)?.toInt()
                if (upstreamStatus == 429) {
                    HttpStatus.TOO_MANY_REQUESTS
                } else {
                    HttpStatus.SERVICE_UNAVAILABLE
                }
            }

            ErrorCode.AUTHZ_SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
            ErrorCode.SCAN_EXECUTION_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR
            ErrorCode.EVENT_PUBLISH_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR
            ErrorCode.INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR
            ErrorCode.SUCCESS -> HttpStatus.OK
        }
}
