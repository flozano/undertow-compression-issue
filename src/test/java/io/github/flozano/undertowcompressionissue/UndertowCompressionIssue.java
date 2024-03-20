package io.github.flozano.undertowcompressionissue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.lang3.RandomStringUtils;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.FutureResponseListener;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UndertowCompressionIssue {

	private static final Logger LOGGER = LoggerFactory.getLogger(UndertowCompressionIssue.class);

	@ParameterizedTest
	@MethodSource("parameters")
	public void testJettyClient(Compression compression, int copyBufferSize, int requestAndResponseSize)
			throws Exception {
		LOGGER.info("Starting Jetty-client test with copyBufferSize={}, requestAndResponseSize={}, compression={}", copyBufferSize,
				requestAndResponseSize, compression.headerValue());
		try (UndertowServer server = new UndertowServer(0, new MirrorServlet(copyBufferSize))) {
			var httpClient = new org.eclipse.jetty.client.HttpClient();
			var uncompressed = RandomStringUtils.randomAlphanumeric(requestAndResponseSize);
			byte[] compressed = compression.compress(uncompressed);
			httpClient.setRequestBufferSize(15_000_000);
			httpClient.setResponseBufferSize(15_000_000);
			try {
				httpClient.start();
				var request = httpClient.newRequest(server.getURI()) //
						.headers(h -> h.add("Content-Encoding", compression.headerValue())) //
						.method("POST") //
						.body(new BytesRequestContent(compressed));
				var listener = new FutureResponseListener(request, 5_000_000);
				request.send(listener);
				var response = listener.get();
				assertEquals(200, response.getStatus());
				assertEquals(uncompressed, response.getContentAsString());
			} finally {
				httpClient.stop();
			}

		}
	}

	public static Stream<Arguments> parameters() {
		List<Arguments> result = new ArrayList<>();
		for (Compression compression : Compression.values()) {
			for (int copyBufferSize : List.of(0, 8192, 1_000_000, 4_000_000)) {
				for (int requestAndResponseSize : List.of(500, 1000, 5000)) {
					result.add(Arguments.of(compression, copyBufferSize, requestAndResponseSize));
				}
			}
		}
		return result.stream();
	}

	static byte[] compressGzip(String payload) {
		try (var baos = new ByteArrayOutputStream()) {
			try (var gos = new GZIPOutputStream(baos)) {
				gos.write(payload.getBytes(StandardCharsets.UTF_8));
			}
			return baos.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	static byte[] compressDeflate(String payload) {
		try (var baos = new ByteArrayOutputStream()) {
			try (var gos = new org.apache.commons.compress.compressors.deflate.DeflateCompressorOutputStream(baos)) {
				gos.write(payload.getBytes(StandardCharsets.UTF_8));
			}
			return baos.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public enum Compression {
		GZIP("gzip", UndertowCompressionIssue::compressGzip), DEFLATE("deflate",
				UndertowCompressionIssue::compressDeflate), NONE("", s -> s.getBytes(StandardCharsets.UTF_8));
		private final String headerValue;
		private Function<String, byte[]> compressor;

		Compression(String headerValue, Function<String, byte[]> compressor) {
			this.headerValue = headerValue;
			this.compressor = compressor;
		}

		public byte[] compress(String payload) {
			return compressor.apply(payload);
		}

		public String headerValue() {
			return this.headerValue;
		}
	}
}
