package com.example;

/** Thrown when the form is not accepting responses yet. */
public class FormClosedException extends RuntimeException {

    public FormClosedException(String message) {
        super(message);
    }
}
