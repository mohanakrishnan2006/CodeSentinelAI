package com.codesentinel.exception;

import com.codesentinel.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralized exception handling across all REST controllers in CodeSentinel AI.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({MissingUploadFileException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ErrorResponse> handleMissingUploadFile(
            Exception ex,
            HttpServletRequest request) {
        return errorResponse(HttpStatus.BAD_REQUEST, "A non-empty ZIP file is required", request);
    }

    @ExceptionHandler({InvalidZipException.class, UnsafeArchiveEntryException.class})
    public ResponseEntity<ErrorResponse> handleInvalidArchive(
            RuntimeException ex,
            HttpServletRequest request) {
        String message = ex instanceof UnsafeArchiveEntryException
                ? "The archive contains an unsafe or unsupported entry"
                : ex.getMessage();
        return errorResponse(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler({UploadLimitExceededException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<ErrorResponse> handleUploadLimitExceeded(
            Exception ex,
            HttpServletRequest request) {
        return errorResponse(HttpStatus.PAYLOAD_TOO_LARGE,
                "The uploaded archive exceeds a configured size or file-count limit", request);
    }

    @ExceptionHandler(UploadNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUploadNotFound(
            UploadNotFoundException ex,
            HttpServletRequest request) {
        return errorResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(ScanNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleScanNotFound(
            ScanNotFoundException ex,
            HttpServletRequest request) {
        return errorResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(UploadNotReadyException.class)
    public ResponseEntity<ErrorResponse> handleUploadNotReady(
            UploadNotReadyException ex,
            HttpServletRequest request) {
        return errorResponse(HttpStatus.CONFLICT, "The upload is not ready to be scanned", request);
    }

    @ExceptionHandler(ScanInProgressException.class)
    public ResponseEntity<ErrorResponse> handleScanInProgress(
            ScanInProgressException ex,
            HttpServletRequest request) {
        return errorResponse(HttpStatus.CONFLICT,
                "The project cannot be deleted while a scan is running", request);
    }

    @ExceptionHandler(UploadStorageException.class)
    public ResponseEntity<ErrorResponse> handleUploadStorageFailure(
            UploadStorageException ex,
            HttpServletRequest request) {
        log.error("Upload storage failed for request to {}", request.getRequestURI(), ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "The upload could not be stored", request);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorResponse> handleMalformedRequest(
            Exception ex,
            HttpServletRequest request) {

        ErrorResponse response = ErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message("Request contains an invalid value or malformed JSON")
                .path(request.getRequestURI())
                .timestamp(Instant.now())
                .build();

        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProjectNotFound(
            ProjectNotFoundException ex,
            HttpServletRequest request) {

        ErrorResponse response = ErrorResponse.builder()
                .status(HttpStatus.NOT_FOUND.value())
                .error(HttpStatus.NOT_FOUND.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .timestamp(Instant.now())
                .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyExists(
            EmailAlreadyExistsException ex,
            HttpServletRequest request) {

        log.warn("Registration conflict: {}", ex.getMessage());

        ErrorResponse response = ErrorResponse.builder()
                .status(HttpStatus.CONFLICT.value())
                .error(HttpStatus.CONFLICT.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .timestamp(Instant.now())
                .build();

        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler({InvalidCredentialsException.class, UsernameNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(
            RuntimeException ex,
            HttpServletRequest request) {

        log.warn("Authentication failed for request to {}", request.getRequestURI());

        ErrorResponse response = ErrorResponse.builder()
                .status(HttpStatus.UNAUTHORIZED.value())
                .error(HttpStatus.UNAUTHORIZED.getReasonPhrase())
                .message("Invalid email address or password")
                .path(request.getRequestURI())
                .timestamp(Instant.now())
                .build();

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationExceptions(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }

        log.warn("Request validation failed on {}: {}", request.getRequestURI(), fieldErrors);

        ErrorResponse response = ErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message("Request validation failed")
                .path(request.getRequestURI())
                .timestamp(Instant.now())
                .validationErrors(fieldErrors)
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request) {

        log.warn("Illegal argument on {}: {}", request.getRequestURI(), ex.getMessage());

        ErrorResponse response = ErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .timestamp(Instant.now())
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex,
            HttpServletRequest request) {

        log.error("Unhandled error processing request to {}: ", request.getRequestURI(), ex);

        ErrorResponse response = ErrorResponse.builder()
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase())
                .message("An unexpected internal error occurred. Please contact system support.")
                .path(request.getRequestURI())
                .timestamp(Instant.now())
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    private ResponseEntity<ErrorResponse> errorResponse(
            HttpStatus status,
            String message,
            HttpServletRequest request) {
        ErrorResponse response = ErrorResponse.builder()
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .timestamp(Instant.now())
                .build();
        return ResponseEntity.status(status).body(response);
    }
}
