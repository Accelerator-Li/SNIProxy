package cc.nium.sni.io;

import java.io.IOException;

@SuppressWarnings("WeakerAccess")
public final class SNIException extends IOException {

    public SNIException(String message) {
        super(message);
    }
}
