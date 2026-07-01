package com.redline.worldcore.api.storage;

/** Runtime wrapper for Region3D storage IO failures. */
public final class CubeStorageException extends RuntimeException {
    public CubeStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public CubeStorageException(String message) {
        super(message);
    }
}
