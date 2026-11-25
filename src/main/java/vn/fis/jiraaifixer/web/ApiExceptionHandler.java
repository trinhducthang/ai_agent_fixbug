package vn.fis.jiraaifixer.web;

import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import vn.fis.jiraaifixer.git.GitException;
import vn.fis.jiraaifixer.jira.JiraException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(JiraException.class)
    ResponseEntity<Map<String, String>> jira(JiraException e) {
        return body(HttpStatus.valueOf(e.status()), e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler({IllegalStateException.class, GitException.class})
    ResponseEntity<Map<String, String>> conflict(RuntimeException e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> other(Exception e) {
        log.error("Lỗi không xử lý", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message == null ? status.getReasonPhrase() : message));
    }
}
