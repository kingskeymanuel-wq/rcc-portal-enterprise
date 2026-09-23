package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.util.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.HashMap;
import java.util.Map;

/**
 * Normalise toutes les erreurs de l'API REST en { "error": { "code", "message" } },
 * exactement le même contrat que src/middleware/error.middleware.js côté Node —
 * le frontend n'a rien à changer pour parler à l'un ou l'autre backend.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(errorBody(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("Invalid request.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody("bad_request", message));
    }

    /** Fichier manquant/absent sur un endpoint multipart (ex. POST /api/transcribe sans "file"). */
    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<Map<String, Object>> handleMultipart(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody("bad_request", "file is required."));
    }

    /**
     * Filet générique. Beaucoup d'exceptions HTTP standard de Spring (415 média non supporté,
     * 405 méthode non autorisée, requête malformée...) implémentent ErrorResponse et portent
     * déjà leur propre statut — les traiter correctement ici évite qu'elles finissent toutes en
     * 500 générique, comme observé pour HttpMediaTypeNotSupportedException lors d'une
     * vérification en conditions réelles (POST /api/transcribe sans Content-Type multipart).
     * ErrorResponse est une interface, pas une Throwable : elle ne peut pas être ciblée
     * directement par @ExceptionHandler, d'où le test instanceof plutôt qu'une méthode dédiée.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.resolve(errorResponse.getStatusCode().value());
            if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
            String detail = errorResponse.getBody() != null ? errorResponse.getBody().getDetail() : null;
            return ResponseEntity.status(status)
                    .body(errorBody(status.name().toLowerCase(), detail != null ? detail : status.getReasonPhrase()));
        }
        // Jamais la stack trace brute au client — seulement loguée côté serveur.
        log.error("Unhandled exception while processing request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody("internal_error", "An unexpected error occurred."));
    }

    private Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> body = new HashMap<>();
        body.put("error", error);
        return body;
    }
}
