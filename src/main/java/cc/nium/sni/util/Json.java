package cc.nium.sni.util;

import cc.nium.sni.annotation.NotNull;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class Json {
    @NotNull
    private static final ObjectMapper objectMapper;
    static {
        final SimpleModule dateTimeModule = new SimpleModule();
        dateTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer());
        objectMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .enable(SerializationFeature.WRITE_DATES_WITH_ZONE_ID)
                .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
                .build()
                .registerModule(dateTimeModule);
    }

    @NotNull
    public static ObjectMapper instance() {
        return objectMapper;
    }

    @NotNull
    @SuppressWarnings({"DuplicateThrows"})
    public static <T> T readValue(@NotNull final byte[] src, @NotNull final Class<T> valueType) throws IOException, JsonParseException, JsonMappingException {
        return objectMapper.readValue(src, valueType);
    }

    @NotNull
    public static byte[] writeValueAsBytes(@NotNull final Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsBytes(value);
    }

    @NotNull
    public static String compressJson(@NotNull final String json) {
        StringBuilder sb = new StringBuilder(json);
        compressJson(sb);
        return sb.toString();
    }

    public static void compressJson(@NotNull final StringBuilder json) {
        boolean isInQuotes = false;
        boolean isInComments = false;
        char lastCh = 0;
        int i = 0;
        for (int j = 0; j < json.length(); j++) {
            char ch = json.charAt(j);
            if (!isInQuotes && (ch == '\r' || ch == '\n')) {
                isInComments = false;
                lastCh = ch;
                continue;
            }
            if (isInComments)
                continue;
            if (!isInQuotes && ch == '/' && lastCh == '/') {
                isInComments = true;
                i--;
                continue;
            }
            if (ch == '\"' && lastCh != '\\')
                isInQuotes = !isInQuotes;
            if (isInQuotes || ch > 32)
                json.setCharAt(i++, ch);
            lastCh = ch;
        }
        json.setLength(i);
    }

    private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static class LocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime dateTime, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            if (dateTime == null) {
                gen.writeNull();
            } else {
                gen.writeString(dateTimeFormatter.format(dateTime));
            }
        }
    }
}
