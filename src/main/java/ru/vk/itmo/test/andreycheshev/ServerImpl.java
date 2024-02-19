package ru.vk.itmo.test.andreycheshev;

import one.nio.http.*;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.andreycheshev.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static one.nio.http.Request.*;
import static one.nio.http.Response.*;

public class ServerImpl extends HttpServer {
    private static final String REQUEST_PATH = "/v0/entity";
    private static final String ID = "id=";
    private final ReferenceDao dao;

    public ServerImpl(ServiceConfig config) throws IOException {
        super(createServerConfig(config));

        Config daoConfig = new Config(config.workingDir(), 100000);

        this.dao = new ReferenceDao(daoConfig);
    }

    public static HttpServerConfig createServerConfig(ServiceConfig config) {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;

        HttpServerConfig serverConfig = new HttpServerConfig();
        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;

        return serverConfig;
    }

    public Response get(final Request request) {
        String id = request.getParameter(ID);
        if (id == null || id.isEmpty()) {
            return new Response(BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> entry = dao.get(fromString(id));

        return (entry == null)
                ? new Response(NOT_FOUND, Response.EMPTY)
                : new Response(OK, entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    public Response put(final Request request) {
        String id = request.getParameter(ID);
        if (id == null || id.isEmpty()) {
            return new Response(BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> entry = new BaseEntry<>(
                fromString(id),
                MemorySegment.ofArray(request.getBody())
        );
        dao.upsert(entry);

        return new Response(CREATED, Response.EMPTY);
    }

    public Response delete(final Request request) {
        String id = request.getParameter(ID);
        if (id == null || id.isEmpty()) {
            return new Response(BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> entry = new BaseEntry<>(fromString(id), null);
        dao.upsert(entry);

        return new Response(ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        String path = request.getPath();
        if (!path.equals(REQUEST_PATH)) {
            session.sendResponse(new Response(BAD_REQUEST, Response.EMPTY));
            return;
        }

        int method = request.getMethod();

        Response response = switch (method) {
            case METHOD_GET -> get(request);
            case METHOD_PUT -> put(request);
            case METHOD_DELETE -> delete(request);
            default -> new Response(METHOD_NOT_ALLOWED, Response.EMPTY);
        };

        session.sendResponse(response);
    }

    private MemorySegment fromString(String data) {
        return MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public synchronized void stop() {
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        super.stop();
    }
}
