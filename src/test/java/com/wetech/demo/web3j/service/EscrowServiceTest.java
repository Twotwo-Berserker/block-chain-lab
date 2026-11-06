package com.wetech.demo.web3j.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.wetech.demo.web3j.contracts.escrow.Escrow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.gas.StaticGasProvider;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class EscrowServiceTest {
    private static final String ADDRESS = "0x" + "11".repeat(20);
    private static final String BUYER = "0x" + "22".repeat(20);
    private static final String SELLER = "0x" + "33".repeat(20);
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new ArrayList<>();
    private HttpServer server;
    private Web3j web3j;
    private EscrowService service;
    private Function<JsonNode, Object> answer;
    private boolean failLogs;

    @BeforeEach void start() throws Exception {
        answer = request -> switch (request.get("method").asText()) {
            case "eth_chainId" -> "0x7a69";
            case "eth_getCode" -> "0x6000";
            case "eth_blockNumber" -> "0x7a"; // 122
            case "eth_getLogs" -> {
                String from = request.get("params").get(0).get("fromBlock").asText();
                int block = Integer.parseInt(from.substring(2), 16);
                yield List.of(event(block, (block - 100) / 10));
            }
            default -> throw new IllegalArgumentException("Unexpected RPC " + request);
        };
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            requests.add(request);
            Object response = failLogs && request.get("method").asText().equals("eth_getLogs")
                    ? Map.of("jsonrpc", "2.0", "id", request.get("id"), "error",
                            Map.of("code", -32000, "message", "test RPC failure"))
                    : Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", answer.apply(request));
            byte[] body = json.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        web3j = Web3j.build(new HttpService("http://127.0.0.1:" + server.getAddress().getPort()));
        service = new EscrowService(web3j, Credentials.create(Keys.createEcKeyPair()),
                new StaticGasProvider(BigInteger.ONE, BigInteger.valueOf(6_721_975)), 31337);
    }

    @AfterEach void stop() { web3j.shutdown(); server.stop(0); }

    @Test void eventQueryUsesInclusiveTenBlockBatchesAndDecodesEveryOrder() throws Exception {
        service.load(ADDRESS, BigInteger.valueOf(100));
        var orders = service.listOrders();
        assertEquals(List.of("0", "1", "2"), orders.stream().map(o -> o.get("id")).toList());
        assertEquals(BUYER, orders.get(0).get("buyer"));
        assertEquals(SELLER, orders.get(0).get("seller"));
        assertEquals("30000000000000000000", orders.get(0).get("amount"));
        var filters = requests.stream().filter(r -> r.get("method").asText().equals("eth_getLogs"))
                .map(r -> r.get("params").get(0)).toList();
        assertEquals(List.of("0x64", "0x6e", "0x78"), filters.stream().map(f -> f.get("fromBlock").asText()).toList());
        assertEquals(List.of("0x6d", "0x77", "0x7a"), filters.stream().map(f -> f.get("toBlock").asText()).toList());
        for (JsonNode filter : filters) {
            assertTrue(filter.get("address").toString().contains(ADDRESS));
            assertEquals(EventEncoder.encode(Escrow.ORDERCREATED_EVENT), filter.get("topics").get(0).asText());
        }
    }

    @Test void rpcFailureIsAnErrorInsteadOfAnEmptyOrderList() throws Exception {
        service.load(ADDRESS, BigInteger.valueOf(100));
        failLogs = true;
        assertTrue(assertThrows(IOException.class, service::listOrders).getMessage().contains("100..109"));
    }

    @Test void unloadedServiceExplainsWhatToDoNext() {
        assertTrue(assertThrows(IllegalStateException.class, service::listOrders).getMessage().contains("/load"));
        assertThrows(IllegalStateException.class, () -> service.resolve(BigInteger.ZERO, false));
    }

    @Test void invalidAddressAndNegativeBlockAreRejectedBeforeRpc() {
        assertThrows(IllegalArgumentException.class, () -> service.load("not-an-address", BigInteger.ZERO));
        assertThrows(IllegalArgumentException.class, () -> service.load(ADDRESS, BigInteger.valueOf(-1)));
        assertThrows(IllegalArgumentException.class, () -> service.getOrder(BigInteger.valueOf(-1)));
        assertTrue(requests.isEmpty());
    }

    @Test void wrongChainIsRejected() {
        answer = r -> "0x1";
        assertThrows(IllegalStateException.class, () -> service.load(ADDRESS, BigInteger.ZERO));
    }

    @Test void futureDeploymentBlockIsRejected() throws Exception {
        service.load(ADDRESS, BigInteger.valueOf(123));
        assertThrows(IllegalArgumentException.class, service::listOrders);
    }

    private Map<String, Object> event(int block, int id) {
        return Map.of("address", ADDRESS, "blockNumber", "0x" + Integer.toHexString(block),
                "transactionHash", "0x" + String.format("%064x", block),
                "transactionIndex", "0x0", "logIndex", "0x0", "removed", false,
                "topics", List.of(EventEncoder.encode(Escrow.ORDERCREATED_EVENT),
                        "0x" + TypeEncoder.encode(new Uint256(id)),
                        "0x" + TypeEncoder.encode(new Address(BUYER)),
                        "0x" + TypeEncoder.encode(new Address(SELLER))),
                "data", "0x" + TypeEncoder.encode(new Address(ADDRESS))
                        + TypeEncoder.encode(new Uint256(new BigInteger("30000000000000000000")))
                        + TypeEncoder.encode(new Uint256(2_000_000_000)));
    }
}
