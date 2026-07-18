package app.scapestack.runelite;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PairingClientTest {
    @Test
    public void approvesCodeWithInstallTokenOutsideJsonBody() {
        final String[] url = new String[1];
        final String[] authorization = new String[1];
        final String[] body = new String[1];
        OkHttpClient http = new OkHttpClient.Builder()
            .addInterceptor(chain -> {
                url[0] = chain.request().url().toString();
                authorization[0] = chain.request().header("Authorization");
                Buffer buffer = new Buffer();
                chain.request().body().writeTo(buffer);
                body[0] = buffer.readUtf8();
                return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(MediaType.parse("application/json"), "{\"ok\":true}"))
                    .build();
            })
            .build();

        PairingClient client = new PairingClient(http);
        assertTrue(client.approve(
            "https://www.scapestack.org/api/sync",
            "Lynx Titan",
            "abcd-efgh",
            "11111111-2222-3333-4444-555555555555",
            "scapestack-test"
        ));
        assertEquals("https://www.scapestack.org/api/account/pair/approve", url[0]);
        assertEquals("Bearer 11111111-2222-3333-4444-555555555555", authorization[0]);
        assertTrue(body[0].contains("\"code\":\"ABCDEFGH\""));
        assertFalse(body[0].contains("token"));
    }

    @Test
    public void derivesProductionAndLocalPairingUrls() {
        assertEquals(
            "https://www.scapestack.org/api/account/pair/approve",
            PairingClient.pairingUrlFromSyncUrl("https://www.scapestack.org/api/sync")
        );
        assertEquals(
            "http://127.0.0.1:4173/api/account/pair/approve",
            PairingClient.pairingUrlFromSyncUrl("http://127.0.0.1:4173/api/sync")
        );
    }
}
