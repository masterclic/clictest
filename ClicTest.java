import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * ClicTest runner for GitHub / Gitea Actions.
 * Same REST flow as ClictestCIPublisher: /getuser, then /execute + /executionStatus per suite x browser.
 * Run with the JDK source launcher (java ClicTest.java), so no build step or dependencies.
 */
// ponytail: trigger/poll logic mirrors ClictestCIPublisher in the ClicTest Jenkins plugin;
// extract a shared core module if the two drift.
public class ClicTest {

	static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

	public static void main(String[] args) throws Exception {
		if (args.length > 0 && "--self-test".equals(args[0])) {
			selfTest();
			return;
		}

		String serviceUrl = required("CLICTEST_SERVICE_URL");
		String envName = required("CLICTEST_ENV_NAME");
		String projectName = required("CLICTEST_PROJECT_NAME");
		String userName = required("CLICTEST_USERNAME");
		String password = dispatchPassword(env("CLICTEST_PASSWORD", ""));
		if (password.isEmpty()) {
			System.err.println("::error::Missing required input CLICTEST_PASSWORD");
			System.exit(1);
		}
		List<Object> suites = asList(parseJson(required("CLICTEST_TEST_SUITES")));
		String jobName = env("CLICTEST_JOB_NAME", env("GITHUB_REPOSITORY", "github-actions"));
		String buildNumber = env("GITHUB_RUN_NUMBER", "0");
		String clientIp = env("CLICTEST_CLIENT_IP", "");
		long pollMillis = Long.parseLong(env("CLICTEST_POLL_INTERVAL_SECONDS", "10")) * 1000L;
		long timeoutMillis = Long.parseLong(env("CLICTEST_TIMEOUT_MINUTES", "60")) * 60_000L;
		int maxParallel = Integer.parseInt(env("CLICTEST_MAX_PARALLEL", "10"));

		Map<String, String> userParams = new LinkedHashMap<>();
		userParams.put("userName", userName);
		userParams.put("password", password);
		userParams.put("envName", envName);
		HttpResponse<String> userResp = get(buildUrl(serviceUrl + "/getuser", userParams));
		String userId = userResp.body() == null ? "" : userResp.body().trim();
		if (userResp.statusCode() != 200 || userId.isEmpty()) {
			System.err.println("::error::ClicTest authentication failed (HTTP " + userResp.statusCode() + ")");
			System.exit(1);
		}

		List<Map<String, String>> tasks = new ArrayList<>();
		for (Object s : suites) {
			Map<String, Object> suite = asMap(s);
			for (Object browser : asList(suite.get("browsers"))) {
				Map<String, String> p = new LinkedHashMap<>();
				p.put("envName", envName);
				p.put("projectName", projectName);
				p.put("userId", userId);
				p.put("jobName", jobName);
				p.put("buildNumber", buildNumber);
				p.put("testSuiteName", str(suite.get("testSuiteName")));
				p.put("version", str(suite.get("testSuiteVersion")));
				p.put("url", str(suite.get("url")));
				p.put("platform", str(suite.get("platform")));
				p.put("browser", str(browser));
				p.put("clientIp", clientIp);
				tasks.add(p);
			}
		}
		if (tasks.isEmpty()) {
			System.err.println("::error::No test suite / browser combinations configured in test-suites.");
			System.exit(1);
		}

		System.out.println("ClicTest: job=" + jobName + " build=" + buildNumber + " executions=" + tasks.size());
		ExecutorService pool = Executors.newFixedThreadPool(Math.min(tasks.size(), maxParallel));
		List<Future<Result>> futures = new ArrayList<>();
		for (Map<String, String> task : tasks) {
			futures.add(pool.submit(() -> executeAndPoll(serviceUrl, task, pollMillis, timeoutMillis)));
		}
		List<Result> results = new ArrayList<>();
		for (Future<Result> f : futures) {
			results.add(f.get());
		}
		pool.shutdownNow();

		boolean failed = results.stream().anyMatch(r -> !r.success);
		report(results, failed);
		System.exit(failed ? 1 : 0);
	}

	static Result executeAndPoll(String serviceUrl, Map<String, String> task, long pollMillis, long timeoutMillis) {
		String label = task.get("testSuiteName") + " / " + task.get("platform") + " / " + task.get("browser");
		try {
			System.out.println("[ClicTest] Triggering " + label);
			HttpResponse<String> exec = get(buildUrl(serviceUrl + "/execute", task));
			if (exec.statusCode() < 200 || exec.statusCode() >= 300) {
				return new Result(label, false, null, "TRIGGER_FAILED", null, null, "HTTP " + exec.statusCode());
			}
			String executionId = str(asMap(parseJson(exec.body())).get("executionId"));
			System.out.println("[ClicTest] " + label + " executionId=" + executionId);

			long start = System.currentTimeMillis();
			while (System.currentTimeMillis() - start <= timeoutMillis) {
				Thread.sleep(pollMillis);
				HttpResponse<String> st = get(serviceUrl + "/executionStatus?executionId="
						+ URLEncoder.encode(executionId, StandardCharsets.UTF_8));
				if (st.statusCode() < 200 || st.statusCode() >= 300) {
					return new Result(label, false, executionId, "STATUS_API_FAILED", null, null, "HTTP " + st.statusCode());
				}
				Map<String, Object> json = asMap(parseJson(st.body()));
				String status = json.get("status") == null ? "UNKNOWN" : str(json.get("status"));
				System.out.println("[ClicTest] " + label + " status=" + status);
				if ("COMPLETED".equalsIgnoreCase(status)) {
					return new Result(label, true, executionId, status, str(json.get("htmlReportUrl")),
							str(json.get("pdfReportUrl")), null);
				}
				if ("FAILED".equalsIgnoreCase(status) || "TERMINATED".equalsIgnoreCase(status)) {
					return new Result(label, false, executionId, status, str(json.get("htmlReportUrl")),
							str(json.get("pdfReportUrl")), "Remote execution ended with status " + status);
				}
			}
			return new Result(label, false, executionId, "TIMEOUT", null, null, "Execution timeout reached.");
		} catch (Exception e) {
			System.err.println("[ClicTest] " + label + " failed: " + e);
			return new Result(label, false, null, "ERROR", null, null, e.toString());
		}
	}

	static void report(List<Result> results, boolean failed) throws Exception {
		StringBuilder md = new StringBuilder("## ClicTest Execution Summary\n\n")
				.append("| Test suite / platform / browser | Status | Execution ID | Report |\n|---|---|---|---|\n");
		String html = "", pdf = "";
		for (Result r : results) {
			System.out.println((r.success ? "PASS " : "FAIL ") + r.label + " status=" + r.status
					+ (r.error == null ? "" : " error=" + r.error));
			String links = (r.html.isEmpty() ? "" : "[HTML](" + r.html + ")")
					+ (r.pdf.isEmpty() ? "" : " [PDF](" + r.pdf + ")");
			md.append("| ").append(r.label).append(" | ").append(r.success ? "✅ " : "❌ ").append(r.status)
					.append(" | ").append(r.executionId).append(" | ").append(links).append(" |\n");
			if (html.isEmpty()) html = r.html;
			if (pdf.isEmpty()) pdf = r.pdf;
		}
		append("GITHUB_STEP_SUMMARY", md.toString());
		append("GITHUB_OUTPUT", "result=" + (failed ? "failure" : "success") + "\nhtml-report-url=" + html
				+ "\npdf-report-url=" + pdf + "\n");
	}

	static final class Result {
		final String label, executionId, status, html, pdf, error;
		final boolean success;

		Result(String label, boolean success, String executionId, String status, String html, String pdf, String error) {
			this.label = label;
			this.success = success;
			this.executionId = executionId == null ? "" : executionId;
			this.status = status;
			this.html = html == null ? "" : html;
			this.pdf = pdf == null ? "" : pdf;
			this.error = error;
		}
	}

	// ---- HTTP / env helpers ----

	static HttpResponse<String> get(String url) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json")
				.timeout(Duration.ofMinutes(2)).GET().build();
		return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
	}

	static String buildUrl(String base, Map<String, String> params) {
		StringBuilder sb = new StringBuilder(base).append('?');
		for (Map.Entry<String, String> e : params.entrySet()) {
			if (sb.charAt(sb.length() - 1) != '?') sb.append('&');
			sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
					.append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
		}
		return sb.toString();
	}

	static String env(String name, String fallback) {
		String v = System.getenv(name);
		return v == null || v.isBlank() ? fallback : v.trim();
	}

	static String required(String name) {
		String v = env(name, null);
		if (v == null) {
			System.err.println("::error::Missing required input " + name);
			System.exit(1);
		}
		return v;
	}

	/**
	 * The configurator's Run now sends the password as the workflow_dispatch input "password".
	 * It is read from the event file and masked here, because passing ${{ inputs.password }} through
	 * "with:" would print it in the step log before any mask applies. Falls back to the password input.
	 */
	static String dispatchPassword(String fallback) {
		String path = System.getenv("GITHUB_EVENT_PATH");
		if (path == null || path.isBlank() || !Files.isRegularFile(Path.of(path))) return fallback;
		String p;
		try {
			p = eventInput(Files.readString(Path.of(path)), "password");
		} catch (Exception e) {
			System.out.println("::warning::Could not read the workflow event file; using the password input.");
			return fallback;
		}
		if (p.isEmpty()) return fallback;
		System.out.println("::add-mask::" + p);
		return p;
	}

	static String eventInput(String eventJson, String name) {
		Object inputs = asMap(parseJson(eventJson)).get("inputs");
		return inputs instanceof Map ? str(asMap(inputs).get(name)) : "";
	}

	static void append(String envFile, String text) throws Exception {
		String path = System.getenv(envFile);
		if (path != null && !path.isBlank()) {
			Files.writeString(Path.of(path), text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
	}

	static String str(Object o) {
		return o == null ? "" : String.valueOf(o);
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> asMap(Object o) {
		if (!(o instanceof Map)) throw new IllegalArgumentException("Expected JSON object but got: " + o);
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	static List<Object> asList(Object o) {
		if (o == null) return new ArrayList<>();
		if (!(o instanceof List)) throw new IllegalArgumentException("Expected JSON array but got: " + o);
		return (List<Object>) o;
	}

	// ---- Minimal JSON parser (objects, arrays, strings, numbers, true/false/null) ----
	// ponytail: hand-rolled to keep the action dependency-free; swap for org.json if inputs get richer.

	static Object parseJson(String s) {
		int[] pos = { 0 };
		Object v = readValue(s, pos);
		skipWs(s, pos);
		if (pos[0] != s.length()) throw new IllegalArgumentException("Trailing JSON at " + pos[0]);
		return v;
	}

	static Object readValue(String s, int[] p) {
		skipWs(s, p);
		if (p[0] >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
		char c = s.charAt(p[0]);
		if (c == '{') {
			Map<String, Object> m = new LinkedHashMap<>();
			p[0]++;
			skipWs(s, p);
			if (s.charAt(p[0]) == '}') { p[0]++; return m; }
			while (true) {
				skipWs(s, p);
				String key = readString(s, p);
				skipWs(s, p);
				expect(s, p, ':');
				m.put(key, readValue(s, p));
				skipWs(s, p);
				if (s.charAt(p[0]) == ',') { p[0]++; continue; }
				expect(s, p, '}');
				return m;
			}
		}
		if (c == '[') {
			List<Object> l = new ArrayList<>();
			p[0]++;
			skipWs(s, p);
			if (s.charAt(p[0]) == ']') { p[0]++; return l; }
			while (true) {
				l.add(readValue(s, p));
				skipWs(s, p);
				if (s.charAt(p[0]) == ',') { p[0]++; continue; }
				expect(s, p, ']');
				return l;
			}
		}
		if (c == '"') return readString(s, p);
		if (s.startsWith("true", p[0])) { p[0] += 4; return Boolean.TRUE; }
		if (s.startsWith("false", p[0])) { p[0] += 5; return Boolean.FALSE; }
		if (s.startsWith("null", p[0])) { p[0] += 4; return null; }
		int start = p[0];
		while (p[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(p[0])) >= 0) p[0]++;
		if (start == p[0]) throw new IllegalArgumentException("Unexpected '" + c + "' at " + start);
		String num = s.substring(start, p[0]);
		// keep integers as text-identical values (e.g. executionId 42 -> "42", not "42.0")
		return num.matches("-?\\d+") ? (Object) Long.valueOf(num) : (Object) Double.valueOf(num);
	}

	static String readString(String s, int[] p) {
		expect(s, p, '"');
		StringBuilder sb = new StringBuilder();
		while (true) {
			char c = s.charAt(p[0]++);
			if (c == '"') return sb.toString();
			if (c != '\\') { sb.append(c); continue; }
			char e = s.charAt(p[0]++);
			switch (e) {
				case 'n': sb.append('\n'); break;
				case 't': sb.append('\t'); break;
				case 'r': sb.append('\r'); break;
				case 'b': sb.append('\b'); break;
				case 'f': sb.append('\f'); break;
				case 'u': sb.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16)); p[0] += 4; break;
				default: sb.append(e);
			}
		}
	}

	static void skipWs(String s, int[] p) {
		while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++;
	}

	static void expect(String s, int[] p, char c) {
		if (p[0] >= s.length() || s.charAt(p[0]) != c)
			throw new IllegalArgumentException("Expected '" + c + "' at " + p[0]);
		p[0]++;
	}

	static void selfTest() {
		Map<String, Object> o = asMap(parseJson(
				"{\"executionId\": 42, \"status\":\"COMPLETED\", \"x\":[true,null,-1.5], \"s\":\"a\\\"b\\u0041\"}"));
		check("42".equals(str(o.get("executionId"))), "integer id");
		check("COMPLETED".equals(o.get("status")), "string");
		check(asList(o.get("x")).size() == 3 && asList(o.get("x")).get(1) == null, "array");
		check("a\"bA".equals(o.get("s")), "escapes");
		List<Object> suites = asList(parseJson(
				"[{\"testSuiteName\":\"S1\",\"browsers\":[\"chrome\",\"firefox\"]},{\"testSuiteName\":\"S2\"}]"));
		check(asList(asMap(suites.get(0)).get("browsers")).size() == 2, "browsers list");
		check(asList(asMap(suites.get(1)).get("browsers")).isEmpty(), "missing browsers");
		Map<String, String> q = new LinkedHashMap<>();
		q.put("a b", "x&y");
		q.put("n", null);
		check("u?a+b=x%26y&n=".equals(buildUrl("u", q)), "url encoding");
		check("p\"w".equals(eventInput("{\"ref\":\"m\",\"inputs\":{\"password\":\"p\\\"w\"},\"n\":1.5e3}", "password")), "event input");
		check("".equals(eventInput("{\"ref\":\"m\",\"commits\":[]}", "password")), "event without inputs");
		System.out.println("self-test OK");
	}

	static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError("self-test failed: " + what);
	}
}
