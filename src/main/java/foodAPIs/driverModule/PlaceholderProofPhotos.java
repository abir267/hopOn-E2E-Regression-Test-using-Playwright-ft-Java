package foodAPIs.driverModule;

import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A handful of tiny, self-contained placeholder images (a few solid-color
 * PNG pixels, embedded as base64 - no test-asset files or network fetch
 * needed) for DriverAPI.markDeliveredWithRandomProof(). The backend's delivery
 * -proof requirement (see DriverAPI class doc) only checks that SOME image was
 * uploaded, not that it's a genuine delivery photo, so these are sufficient.
 */
final class PlaceholderProofPhotos {

    record Photo(byte[] bytes, String filename, String mimeType) {
    }

    // 1x1 red-pixel PNG - CONFIRMED this exact byte string uploads successfully
    // (2026-09-19 probe, 200 with a real hosted url back). Only one actual
    // image is embedded: the backend doesn't inspect photo content, only that
    // some valid image was uploaded, so "randomly complete it" just needs a
    // different filename each time, not different bytes.
    private static final String CONFIRMED_WORKING_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";

    private PlaceholderProofPhotos() {
    }

    static Photo random() {
        byte[] bytes = Base64.getDecoder().decode(CONFIRMED_WORKING_PNG_BASE64);
        int pick = ThreadLocalRandom.current().nextInt(1000);
        return new Photo(bytes, "delivery-proof-" + pick + ".png", "image/png");
    }
}
