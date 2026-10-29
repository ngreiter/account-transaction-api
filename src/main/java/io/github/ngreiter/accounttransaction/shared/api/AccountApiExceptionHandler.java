package io.github.ngreiter.accounttransaction.shared.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;

import io.github.ngreiter.accounttransaction.account.application.AccountCreationException;
import io.github.ngreiter.accounttransaction.account.application.AccountNotFoundException;
import io.github.ngreiter.accounttransaction.transaction.application.TransactionApplicationException;

@RestControllerAdvice
public class AccountApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AccountApiExceptionHandler.class);

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> handleInvalidRequest(Exception exception, HttpServletRequest request) {
        Throwable cause = rootCause(exception);
        log.warn("Requisição inválida: method={} path={} exception={} cause={}", request.getMethod(),
                request.getRequestURI(), exception.getClass().getSimpleName(), cause.getClass().getSimpleName());
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_ERROR", "Verifique os dados enviados."));
    }

    @ExceptionHandler(AccountCreationException.class)
    ResponseEntity<String> handleAccountCreation(AccountCreationException exception, HttpServletRequest request) {
        log.warn("Falha ao criar conta: method={} path={} exception={} error={} reason={}", request.getMethod(),
                request.getRequestURI(), exception.getClass().getSimpleName(), exception.error(),
                exception.getMessage());
        HttpStatus status = switch (exception.error()) {
            case ACCOUNT_CONFLICT, IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(exception.responseBody());
    }

    @ExceptionHandler(TransactionApplicationException.class)
    ResponseEntity<String> handleTransaction(TransactionApplicationException exception, HttpServletRequest request) {
        log.warn("Falha em transação: method={} path={} exception={} error={} reason={}", request.getMethod(),
                request.getRequestURI(), exception.getClass().getSimpleName(), exception.error(),
                exception.getMessage());
        HttpStatus status = switch (exception.error()) {
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
            case INSUFFICIENT_FUNDS -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(exception.responseBody());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException exception, HttpServletRequest request) {
        log.warn("Parâmetro inválido: method={} path={} exception={} reason={}", request.getMethod(),
                request.getRequestURI(), exception.getClass().getSimpleName(), exception.getMessage());
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_ERROR", "Verifique os dados enviados."));
    }

    @ExceptionHandler(AccountNotFoundException.class)
    ResponseEntity<ApiError> handleAccountNotFound(AccountNotFoundException exception, HttpServletRequest request) {
        log.warn("Conta não encontrada: method={} path={} exception={} reason={}", request.getMethod(),
                request.getRequestURI(), exception.getClass().getSimpleName(), exception.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("RESOURCE_NOT_FOUND", "Recurso não encontrado."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Erro interno não tratado: method={} path={} exception={}", request.getMethod(),
                request.getRequestURI(), exception.getClass().getSimpleName(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "Ocorreu um erro interno."));
    }

    private Throwable rootCause(Throwable exception) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
