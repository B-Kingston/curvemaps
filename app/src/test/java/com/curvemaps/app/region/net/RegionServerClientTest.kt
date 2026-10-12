package com.curvemaps.app.region.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class RegionServerClientTest {

    private val servers = mutableListOf<TestHttpServer>()
    private val lastRequest = AtomicReference<RequestRecord>()

    private data class RequestRecord(
        val method: String,
        val path: String,
        val body: String,
        val csrf: String,
        val authorization: String = "",
    )

    @After
    fun tearDown() {
        servers.forEach { it.close() }
    }

    private fun fingerprint() = "f".repeat(64)

    private fun jobJson(state: String, progress: Double): String = """
        {"id":"job-1","regionId":"monaco","regionName":"Monaco","state":"$state","step":"tiles",
         "progress":$progress,"message":"working","error":"","cached":false,"artifactSize":1024,
         "artifactSha256":"${"b".repeat(64)}",
         "downloadUrl":"/api/v1/artifacts/monaco/${fingerprint()}/monaco.motomap"}
    """.trimIndent()

    private fun start(): TestHttpServer {
        val server = TestHttpServer { request ->
            lastRequest.set(
                RequestRecord(
                    method = request.method,
                    path = request.path,
                    body = request.body.decodeToString(),
                    csrf = request.header("X-CSRF-Token").orEmpty(),
                    authorization = request.header("Authorization").orEmpty(),
                ),
            )
            when {
                request.path == "/api/v1/health" ->
                    TestHttpServer.Response(
                        200,
                        """{"status":"ok","version":"0.1.0","generation":{"enabled":true,"message":""}}""",
                    )
                request.path == "/api/v1/csrf" -> TestHttpServer.Response(200, """{"token":"t0ken"}""")
                request.path == "/api/v1/catalog" -> TestHttpServer.Response(
                    200,
                    """
                    {"regions":[
                      {"id":"monaco","name":"Monaco","coverage":"Monaco","disabled":false,"maxZoom":14,
                       "sourceDate":"260928","sourceSha256":"${"a".repeat(64)}","sourcePinned":true,
                       "artifact":{"available":true,"fingerprint":"${fingerprint()}","size":1024,
                         "sha256":"${"b".repeat(64)}","generatedAt":"2026-10-01T00:00:00Z",
                         "downloadUrl":"/api/v1/artifacts/monaco/${fingerprint()}/monaco.motomap"}},
                      {"id":"broken","name":"Broken","disabled":true,"sourceDate":"260928"},
                      {"id":"tasmania","name":"Tasmania","sourceDate":"261005","tier":"pro",
                       "requested":true,"extractId":"tasmania","artifact":{"available":false}}
                    ]}
                    """.trimIndent(),
                )
                request.path == "/api/v1/builds" && request.method == "POST" ->
                    TestHttpServer.Response(202, jobJson("running", progress = 0.4))
                request.path == "/api/v1/builds/job-1" ->
                    TestHttpServer.Response(200, jobJson("completed", progress = 1.0))
                request.path == "/api/v1/builds/job-1/cancel" ->
                    TestHttpServer.Response(200, jobJson("canceled", progress = 0.0))
                request.path.startsWith("/api/v1/extracts?") -> TestHttpServer.Response(
                    200,
                    """
                    {"extracts":[
                      {"id":"tasmania","name":"Tasmania","parent":"australia",
                       "path":["Australia and Oceania","Australia"],"regionId":"tasmania",
                       "bytes":53952987,"eligible":true},
                      {"id":"australia","name":"Australia","path":["Australia and Oceania"],
                       "regionId":"australia","bytes":966467067,"eligible":false,
                       "reason":"Australia is 922 MiB; this server builds extracts up to 768 MiB."}
                    ],"maxExtractBytes":805306368}
                    """.trimIndent(),
                )
                request.path == "/api/v1/builds/pro" ->
                    TestHttpServer.Response(402, """{"error":"CurveMaps Pro required","tier":"pro"}""")
                request.path == "/api/v1/builds/pro-silent" ->
                    TestHttpServer.Response(402, "")
                request.path == "/api/v1/builds/broken" ->
                    TestHttpServer.Response(429, """{"error":"the queue is full"}""")
                else -> TestHttpServer.Response(404, """{"error":"not found"}""")
            }
        }
        servers += server
        return server
    }

    private fun client() = RegionServerClient(RegionHttpClient(allowInsecure = { true }))

    @Test
    fun normalizesServerAddresses() {
        val client = client()
        assertEquals("https://maps.example.net", client.normalizeBaseUrl("maps.example.net"))
        assertEquals("https://maps.example.net", client.normalizeBaseUrl("https://maps.example.net/"))
        assertEquals("https://maps.example.net:8443", client.normalizeBaseUrl("https://maps.example.net:8443"))
        assertEquals("http://192.168.1.10:8080", client.normalizeBaseUrl("http://192.168.1.10:8080/"))
        assertEquals("https://example.net/base", client.normalizeBaseUrl("example.net/base/"))
        for (bad in listOf("", "   ", "ftp://example.net", "https://")) {
            try {
                client.normalizeBaseUrl(bad)
                fail("\"$bad\" accepted")
            } catch (expected: RegionHttpException) {
            }
        }
    }

    @Test
    fun fetchesTheCatalog() {
        val server = start()
        val catalog = client().catalog(server.baseUrl)
        assertEquals(3, catalog.size)
        val monaco = catalog.first()
        assertEquals("monaco", monaco.id)
        assertTrue(monaco.sourcePinned)
        assertTrue(monaco.hasCachedArtifact)
        assertEquals(1024L, monaco.artifact?.size)
        assertTrue(catalog[1].disabled)
        assertEquals("free", monaco.tier)
        val tasmania = catalog[2]
        assertTrue(tasmania.isPro)
        assertTrue(tasmania.requested)
        assertEquals("tasmania", tasmania.extractId)
        assertFalse(tasmania.hasCachedArtifact)
    }

    @Test
    fun searchesExtractsByNameAndByCoordinate() {
        val server = start()
        val client = client()
        val results = client.extracts(server.baseUrl, "new south wales")
        assertEquals("/api/v1/extracts?q=new+south+wales&limit=10", lastRequest.get().path)
        assertEquals(2, results.size)
        val tasmania = results[0]
        assertEquals("tasmania", tasmania.regionId)
        assertEquals("Australia", tasmania.parentLabel)
        assertEquals(53_952_987L, tasmania.bytes)
        assertTrue(tasmania.eligible)
        assertFalse(results[1].eligible)
        assertTrue(results[1].reason.contains("768 MiB"))
        client.extracts(server.baseUrl, null, lat = -42.88, lon = 147.33, limit = 5)
        assertEquals("/api/v1/extracts?lat=-42.88&lon=147.33&limit=5", lastRequest.get().path)
    }

    @Test
    fun requestsExtractBuildsWithTheExtractId() {
        val server = start()
        val client = client()
        val job = client.requestExtractBuild(server.baseUrl, "t0ken", "us/oregon")
        assertTrue(job.isActive)
        val request = lastRequest.get()
        assertEquals("POST", request.method)
        assertEquals("t0ken", request.csrf)
        assertTrue(request.body.contains("\"extractId\":\"us/oregon\""))
        assertFalse(request.body.contains("regionId"))
    }

    @Test
    fun paidMapsAnswerWithAProRequiredMessage() {
        val server = start()
        val client = client()
        for (path in listOf("pro", "pro-silent")) {
            try {
                client.job(server.baseUrl, path)
                fail("402 accepted")
            } catch (expected: RegionHttpException) {
                assertEquals(402, expected.statusCode)
                assertFalse("402 must not be retried", expected.transient)
                assertTrue(expected.message!!.contains("Pro"))
            }
        }
    }

    @Test
    fun sendsAnEntitlementTokenOnlyWhenOneExists() {
        val server = start()
        RegionServerClient(RegionHttpClient(allowInsecure = { true })).catalog(server.baseUrl)
        assertEquals("", lastRequest.get().authorization)
        RegionServerClient(RegionHttpClient(allowInsecure = { true }, entitlementToken = { "ent-123" }))
            .catalog(server.baseUrl)
        assertEquals("Bearer ent-123", lastRequest.get().authorization)
    }

    @Test
    fun reportsGenerationAvailabilityFromHealth() {
        val server = start()
        val health = client().health(server.baseUrl)
        assertTrue(health!!.getJSONObject("generation").getBoolean("enabled"))
        assertEquals("0.1.0", health.getString("version"))
    }

    @Test
    fun sendsCsrfTokenAndRegionOnBuildRequests() {
        val server = start()
        val client = client()
        val token = client.csrfToken(server.baseUrl)
        assertEquals("t0ken", token)
        val job = client.requestBuild(server.baseUrl, token, "monaco")
        assertEquals("running", job.state)
        assertTrue(job.isActive)
        val request = lastRequest.get()
        assertEquals("POST", request.method)
        assertEquals("t0ken", request.csrf)
        assertTrue(request.body.contains("\"regionId\":\"monaco\""))
    }

    @Test
    fun pollsAndCancelsJobs() {
        val server = start()
        val client = client()
        val completed = client.job(server.baseUrl, "job-1")
        assertTrue(completed.isCompleted)
        assertFalse(completed.isActive)
        assertEquals(1.0, completed.progress, 0.0001)
        assertTrue(completed.downloadUrl.contains("/api/v1/artifacts/"))
        val canceled = client.cancelBuild(server.baseUrl, "t0ken", "job-1")
        assertTrue(canceled.isCanceled)
    }

    @Test
    fun resolvesRelativeDownloadUrlsAgainstTheBase() {
        val client = client()
        assertEquals(
            "https://maps.example.net/api/v1/artifacts/x",
            client.resolve("https://maps.example.net", "/api/v1/artifacts/x"),
        )
        assertEquals(
            "https://maps.example.net/api/v1/artifacts/x",
            client.resolve("https://maps.example.net", "https://maps.example.net:443/api/v1/artifacts/x")
                .replace(":443", ""),
        )
    }

    @Test
    fun refusesDownloadUrlsOnAnotherOrigin() {
        val client = client()
        for (foreign in listOf(
            "https://other.example.net/file",
            "http://maps.example.net/file",
            "https://maps.example.net:8443/file",
        )) {
            try {
                client.resolve("https://maps.example.net", foreign)
                fail("$foreign accepted")
            } catch (expected: RegionHttpException) {
                assertTrue(expected.message!!.contains("different address"))
            }
        }
    }

    @Test
    fun acceptsAnAddressCopiedFromAnApiUrl() {
        val client = client()
        assertEquals("https://maps.example.net", client.normalizeBaseUrl("https://Maps.Example.net/api/v1/catalog"))
        assertEquals("https://maps.example.net/base", client.normalizeBaseUrl("maps.example.net/base/api/v1/"))
    }

    @Test
    fun triesPlainHttpOnlyAsAnAllowedFallbackForSchemelessAddresses() {
        val client = client()
        assertEquals(
            listOf("https://192.168.1.20:8080", "http://192.168.1.20:8080"),
            client.candidateBaseUrls("192.168.1.20:8080") { true },
        )
        assertEquals(
            listOf("https://192.168.1.20:8080"),
            client.candidateBaseUrls("192.168.1.20:8080") { false },
        )
        // An explicit scheme is always taken literally.
        assertEquals(
            listOf("https://192.168.1.20:8080"),
            client.candidateBaseUrls("https://192.168.1.20:8080") { true },
        )
    }

    @Test
    fun serverErrorsSurfaceTheirMessage() {
        val server = start()
        val client = client()
        try {
            client.job(server.baseUrl, "broken")
            fail("error response accepted")
        } catch (expected: RegionHttpException) {
            assertEquals(429, expected.statusCode)
            assertEquals("the queue is full", expected.message)
        }
        try {
            client.catalog("${server.baseUrl}/api/v1/nope")
            fail("missing endpoint accepted")
        } catch (expected: RegionHttpException) {
            assertEquals(404, expected.statusCode)
        }
    }
}
