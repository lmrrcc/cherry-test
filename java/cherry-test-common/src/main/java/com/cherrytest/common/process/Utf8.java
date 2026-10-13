package com.cherrytest.common.process;

import com.cherrytest.common.rustcompat.AnyhowError;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class Utf8 {
    private Utf8() {}

    public static String decodeStrict(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new AnyhowError("invalid utf-8 sequence");
        }
    }

    public static String decodeLossy(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
