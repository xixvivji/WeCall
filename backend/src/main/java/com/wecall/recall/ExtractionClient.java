package com.wecall.recall;

import java.util.UUID;

/** Backend port for source-bound, structured extraction. Implementations must validate
 * request identity, source hash, rule schema and quote before returning a response.
 * This contract never approves conditions or grants access to business datasets.
 */
@FunctionalInterface
public interface ExtractionClient {
    record Response(ExtractionModels.Result result, String raw) {}

    /** Stable failure code and optional bounded upstream response for audit storage. */
    class Failed extends RuntimeException {
        public final String code, raw;
        public Failed(String code, String raw) {
            super(code);
            this.code = code;
            this.raw = raw;
        }
    }

    /** Extract from the exact source snapshot; sha is its UTF-8 SHA-256 digest. */
    Response extract(UUID requestId, String source, String sha);
}
