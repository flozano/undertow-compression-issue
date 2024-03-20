package io.github.flozano.undertowcompressionissue;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;

import org.xnio.Xnio;
import org.xnio.XnioWorker;

import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.conduits.GzipStreamSourceConduit;
import io.undertow.conduits.InflatingStreamSourceConduit;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.encoding.RequestEncodingHandler;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.util.ImmediateInstanceHandle;
import io.undertow.util.Headers;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;

public class UndertowServer implements AutoCloseable {

	private static final ClassLoader CLASS_LOADER = UndertowServer.class.getClassLoader();

	private final DeploymentManager deploymentManager;
	private final PathHandler pathHandler;
	private final RequestEncodingHandler requestEncodingHandler;
	private final Undertow undertow;

	private final XnioWorker worker;

	public UndertowServer(HttpServlet servlet) throws ServletException {
		this(0, servlet);
	}

	public UndertowServer(int port, HttpServlet servlet) throws ServletException {

		deploymentManager = Servlets.newContainer().addDeployment(buildDeploymentInfo(servlet, Map.of()));
		deploymentManager.deploy();

		pathHandler = Handlers.path(Handlers.redirect("/")).addPrefixPath("/",
				requireNonNull(deploymentManager).start());
		requestEncodingHandler = new CompressedRequestEncodingHandler(pathHandler);

		worker = xnio();

		undertow = Undertow.builder().addHttpListener(port, "0.0.0.0").setWorker(worker)
				.setDirectBuffers(true).setHandler(requestEncodingHandler).setServerOption(UndertowOptions.DECODE_URL, true)
				.setServerOption(UndertowOptions.URL_CHARSET, "utf-8").build();
		undertow.start();

	}

	public int getPort() {
		return ((InetSocketAddress) undertow.getListenerInfo().get(0).getAddress()).getPort();
	}

	public URI getURI() {
		return URI.create("http://localhost:" + getPort());
	}

	static DeploymentInfo buildDeploymentInfo(HttpServlet servlet, Map<String, String> initParameters) {
		DeploymentInfo di = Servlets.deployment().setClassLoader(CLASS_LOADER).setEagerFilterInit(true)
				.setSendCustomReasonPhraseOnError(true).setAllowNonStandardWrappers(true).setContextPath("/")
				.setDeploymentName("ROOT.war").setClassLoader(UndertowServer.class.getClassLoader()).addServlets(
						configureServletToServletInfo("root", servlet));
		for (Map.Entry<String, String> param : initParameters.entrySet()) {
			di.addInitParameter(param.getKey(), param.getValue());
		}
		return di;

	}

	static ServletInfo configureServletToServletInfo(String name, HttpServlet servlet) {
		ServletInfo si = Servlets.servlet(name, servlet.getClass(), () -> new ImmediateInstanceHandle<>(servlet));
		si.setAsyncSupported(true);
		si.setLoadOnStartup(1);
		si.setEnabled(true);
		si.addMapping("/*");
		return si;
	}

	static XnioWorker xnio() {
		return Xnio.getInstance().createWorkerBuilder().setMaxWorkerPoolSize(100).setCoreWorkerPoolSize(10)
				.setWorkerName("undertow-worker").build();
	}

	public void close() throws Exception {
		undertow.stop();
		deploymentManager.undeploy();
	}
}

class CompressedRequestEncodingHandler extends RequestEncodingHandler {

	public CompressedRequestEncodingHandler(HttpHandler next) {
		super(next);
		addEncoding("gzip", GzipStreamSourceConduit.WRAPPER);
		addEncoding("deflate", InflatingStreamSourceConduit.WRAPPER);
	}

	@Override
	public void handleRequest(HttpServerExchange exchange) throws Exception {
		String contentEncodingBefore = exchange.getRequestHeaders().getFirst(Headers.CONTENT_ENCODING);
		super.handleRequest(exchange);
		String contentEncodingAfter = exchange.getRequestHeaders().getFirst(Headers.CONTENT_ENCODING);
		if (contentEncodingAfter == null && contentEncodingBefore != null) {
			/*
			 * Hack to prevent StringHttpMessageConverter from truncating the uncompressed content
			 * as described in https://github.com/spring-projects/spring-framework/issues/32162
			 * This applies to SF 6.1.x (SB 3.2.x) but not to SF 6.0.x (SB 3.1.x)
			 */
			exchange.getRequestHeaders().remove(Headers.CONTENT_LENGTH);
		}
	}
}