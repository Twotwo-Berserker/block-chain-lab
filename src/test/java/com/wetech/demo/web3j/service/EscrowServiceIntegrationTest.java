package com.wetech.demo.web3j.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wetech.demo.web3j.contracts.escrow.Escrow;
import com.wetech.demo.web3j.contracts.simplestorage.SimpleStorage;
import com.wetech.demo.web3j.contracts.wqltoken.WQLToken;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.exceptions.TransactionException;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.gas.StaticGasProvider;

import java.math.BigInteger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real local-chain calls with random ephemeral accounts, NOT three-person POTOS acceptance. */
class EscrowServiceIntegrationTest {
    private static final BigInteger UNIT = BigInteger.TEN.pow(18);
    private final List<Map<String, String>> transactions = new ArrayList<>();

    @Test void completeEscrowLifecycleAndReceiptFailuresOnLocalChain() throws Exception {
        HttpService transport = new HttpService("http://127.0.0.1:18545");
        Web3j web3j = Web3j.build(transport);
        try {
            assertEquals(BigInteger.valueOf(31337), web3j.ethChainId().send().getChainId(),
                    "Integration tests are restricted to local chain 31337");
            var gas = new StaticGasProvider(BigInteger.valueOf(2_000_000_000L), BigInteger.valueOf(6_721_975));
            Credentials backend = freshFundedAccount(transport);
            Credentials buyer = freshFundedAccount(transport);
            Credentials seller = freshFundedAccount(transport);
            System.out.println("LOCAL ONLY | chainId=31337 | all three roles simulated by one test process");
            System.out.println("backend=" + backend.getAddress());
            System.out.println("buyer=" + buyer.getAddress());
            System.out.println("seller=" + seller.getAddress());
            var backendTx = new RawTransactionManager(web3j, backend, 31337);
            var buyerTx = new RawTransactionManager(web3j, buyer, 31337);
            var sellerTx = new RawTransactionManager(web3j, seller, 31337);
            WQLToken token = WQLToken.deploy(web3j, buyerTx, gas).send();
            record("deploy token (local only)", token.getTransactionReceipt().orElseThrow());
            record("mint 100 tokens", token.mint(buyer.getAddress(), UNIT.multiply(BigInteger.valueOf(100))).send());

            EscrowService service = new EscrowService(web3j, backend, gas, 31337);
            Escrow escrow = service.deploy().join();
            record("deploy Escrow", escrow.getTransactionReceipt().orElseThrow());
            assertEquals(backend.getAddress(), service.getAddress().join().get("arbiter"));
            Escrow buyerEscrow = Escrow.load(escrow.getContractAddress(), web3j, buyerTx, gas);
            Escrow sellerEscrow = Escrow.load(escrow.getContractAddress(), web3j, sellerTx, gas);
            BigInteger deadline = web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false)
                    .send().getBlock().getTimestamp().add(BigInteger.valueOf(1800));
            BigInteger amount = UNIT.multiply(BigInteger.valueOf(30));
            record("createOrder 0", buyerEscrow.createOrder(seller.getAddress(), token.getContractAddress(),
                    amount, deadline, "member1 local normal order").send());
            // Force the second creation into another query batch to detect truncated log scans.
            rpc(transport, "hardhat_mine", List.of("0xb"));
            record("createOrder 1", buyerEscrow.createOrder(seller.getAddress(), token.getContractAddress(),
                    amount, deadline, "member1 local disputed order").send());
            record("approve 60 tokens", token.approve(escrow.getContractAddress(), amount.multiply(BigInteger.TWO)).send());
            record("deposit 0", buyerEscrow.deposit(BigInteger.ZERO).send());
            record("deposit 1", buyerEscrow.deposit(BigInteger.ONE).send());
            assertEquals("Paid", service.getOrder(BigInteger.ZERO).join().get("status"));
            assertEquals(UNIT.multiply(BigInteger.valueOf(40)), token.balanceOf(buyer.getAddress()).send());
            record("seller dispute 1", sellerEscrow.dispute(BigInteger.ONE).send());
            assertEquals("Disputed", service.getOrder(BigInteger.ONE).join().get("status"));
            var listed = service.listOrders();
            assertEquals(List.of("0", "1"), listed.stream().map(o -> o.get("id")).toList());

            Throwable failed = assertThrows(Exception.class, () -> service.resolve(BigInteger.ZERO, true).join());
            TransactionException wrongState = transactionException(failed);
            TransactionReceipt failedReceipt = wrongState.getTransactionReceipt().orElseThrow();
            assertFalse(failedReceipt.isStatusOK());
            assertTrue(failedReceipt.getGasUsed().signum() > 0);
            assertEquals(backend.getAddress(), failedReceipt.getFrom());
            record("resolve 0 rejected: wrong status", failedReceipt);
            TransactionException wrongRole = assertThrows(TransactionException.class,
                    () -> sellerEscrow.resolve(BigInteger.ONE, false).send());
            record("seller resolve 1 rejected: not arbiter", wrongRole.getTransactionReceipt().orElseThrow());
            assertEquals("Disputed", service.getOrder(BigInteger.ONE).join().get("status"));

            TransactionReceipt refunded = service.resolve(BigInteger.ONE, false).join();
            record("resolve 1 refund", refunded);
            assertEquals(backend.getAddress(), refunded.getFrom());
            assertEquals("Refunded", service.getOrder(BigInteger.ONE).join().get("status"));
            assertEquals(UNIT.multiply(BigInteger.valueOf(70)), token.balanceOf(buyer.getAddress()).send());
            assertEquals(BigInteger.ONE, Escrow.getOrderResolvedEvents(refunded).get(0).id);
            assertFalse(Escrow.getOrderResolvedEvents(refunded).get(0).sellerWins);
            var transfer = WQLToken.getTransferEvents(refunded).get(0);
            assertEquals(escrow.getContractAddress(), transfer.from);
            assertEquals(buyer.getAddress(), transfer.to);
            assertEquals(amount, transfer.value);

            record("confirm 0", buyerEscrow.confirm(BigInteger.ZERO).send());
            assertEquals("Completed", service.getOrder(BigInteger.ZERO).join().get("status"));
            assertEquals(amount, token.balanceOf(seller.getAddress()).send());
            assertEquals(BigInteger.ZERO, token.balanceOf(escrow.getContractAddress()).send());
            assertThrows(Exception.class, () -> service.getOrder(BigInteger.valueOf(99)).join());

            EscrowService restored = new EscrowService(web3j, backend, gas, 31337);
            restored.load(escrow.getContractAddress(), service.getDeployBlock());
            assertEquals(listed, restored.listOrders());
            assertEquals("Refunded", restored.getOrder(BigInteger.ONE).join().get("status"));
            SimpleStorage storage = SimpleStorage.deploy(web3j, backendTx, gas).send();
            record("SimpleStorage deploy (local only)", storage.getTransactionReceipt().orElseThrow());
            record("SimpleStorage set 100 (local only)", storage.set(BigInteger.valueOf(100)).send());
            assertEquals(BigInteger.valueOf(100), storage.get().send());

            Path output = Path.of("build", "local-evidence", "local-integration.json");
            Files.createDirectories(output.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                    "classification", "LOCAL_SIMULATION_NOT_GROUP_ACCEPTANCE", "chainId", 31337,
                    "recordedAtUtc", Instant.now().toString(),
                    "accounts", Map.of("backend", backend.getAddress(), "buyer", buyer.getAddress(), "seller", seller.getAddress()),
                    "escrowAddress", escrow.getContractAddress(), "tokenAddress", token.getContractAddress(),
                    "deployBlock", service.getDeployBlock().toString(), "transactions", transactions,
                    "finalState", Map.of("order0", "Completed", "order1", "Refunded", "buyerTokens", "70", "sellerTokens", "30", "escrowTokens", "0")));
            System.out.println("PASS: real local transactions, >10-block event scan, state/role failures, refunds, settlement, reload, SimpleStorage=100");
        } finally { web3j.shutdown(); }
    }

    private Credentials freshFundedAccount(HttpService transport) throws Exception {
        Credentials account = Credentials.create(Keys.createEcKeyPair());
        rpc(transport, "hardhat_setBalance", List.of(account.getAddress(), "0x" + UNIT.multiply(BigInteger.TEN).toString(16)));
        return account;
    }

    @SuppressWarnings("rawtypes")
    private void rpc(HttpService transport, String method, List<String> params) throws IOException {
        Response response = new Request<>(method, params, transport, Response.class).send();
        if (response.hasError()) throw new IOException(response.getError().getMessage());
    }

    private void record(String operation, TransactionReceipt receipt) {
        transactions.add(Map.of("operation", operation, "hash", receipt.getTransactionHash(),
                "block", receipt.getBlockNumber().toString(), "from", receipt.getFrom(),
                "status", receipt.getStatus(), "gasUsed", receipt.getGasUsed().toString()));
        System.out.printf("%s | block=%s status=%s gas=%s tx=%s%n", operation,
                receipt.getBlockNumber(), receipt.getStatus(), receipt.getGasUsed(), receipt.getTransactionHash());
    }

    private TransactionException transactionException(Throwable error) {
        while (error != null) {
            if (error instanceof TransactionException tx) return tx;
            error = error.getCause();
        }
        throw new AssertionError("Expected TransactionException with failed on-chain receipt");
    }
}
