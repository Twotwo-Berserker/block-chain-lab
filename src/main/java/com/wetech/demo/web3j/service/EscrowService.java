package com.wetech.demo.web3j.service;

import com.wetech.demo.web3j.contracts.escrow.Escrow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.abi.EventEncoder;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.gas.ContractGasProvider;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Member 1: contract access; member 2 maps these methods to the six REST endpoints. */
@Service
public class EscrowService {
    private static final String[] STATUS = {"Created", "Paid", "Completed", "Refunded", "Disputed"};
    private static final BigInteger LOG_BATCH_SIZE = BigInteger.TEN;

    private final Web3j web3j;
    private final Credentials credentials;
    private final ContractGasProvider gasProvider;
    private final RawTransactionManager transactionManager;
    private final long chainId;
    private final Object writeLock = new Object();

    // One immutable binding prevents a concurrent load from mixing address and deployment block.
    private record Binding(Escrow contract, String address, BigInteger deployBlock) { }
    private volatile Binding binding;

    public EscrowService(Web3j web3j, Credentials credentials, ContractGasProvider gasProvider,
                         @Value("${web3j.chain-id:60600}") long chainId) {
        this.web3j = web3j;
        this.credentials = credentials;
        this.gasProvider = gasProvider;
        this.chainId = chainId;
        this.transactionManager = new RawTransactionManager(web3j, credentials, chainId);
    }

    /** Backend account becomes arbiter; signed transactions include the configured chain ID. */
    public CompletableFuture<Escrow> deploy() {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (writeLock) {
                try {
                    requireChain();
                    Escrow deployed = Escrow.deploy(web3j, transactionManager, gasProvider,
                            credentials.getAddress()).send();
                    TransactionReceipt receipt = deployed.getTransactionReceipt().orElseThrow();
                    binding = new Binding(deployed, deployed.getContractAddress(), receipt.getBlockNumber());
                    return deployed;
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }
        });
    }

    /** Restore the binding after a restart. Use the real deployment block to avoid missed events. */
    public void load(String address, BigInteger fromBlock) throws IOException {
        if (address == null || !address.matches("(?i)0x[0-9a-f]{40}")
                || address.equalsIgnoreCase("0x" + "0".repeat(40))) {
            throw new IllegalArgumentException("address must be a nonzero 20-byte hex address");
        }
        requireNonNegative(fromBlock, "fromBlock");
        requireChain();
        var code = web3j.ethGetCode(address, DefaultBlockParameterName.LATEST).send();
        if (code.hasError()) throw new IOException("eth_getCode: " + code.getError().getMessage());
        if (code.getCode() == null || code.getCode().equals("0x") || code.getCode().equals("0x0")) {
            throw new IllegalArgumentException("No contract deployed at address");
        }
        binding = new Binding(Escrow.load(address, web3j, transactionManager, gasProvider), address, fromBlock);
    }

    public String getContractAddress() { return requireContract().address(); }
    public BigInteger getDeployBlock() { return requireContract().deployBlock(); }

    public CompletableFuture<Map<String, String>> getAddress() {
        Binding current = requireContract();
        return current.contract().arbiter().sendAsync().thenApply(arbiter -> Map.of(
                "contractAddress", current.address(), "arbiter", arbiter));
    }

    public CompletableFuture<Map<String, String>> getOrder(BigInteger id) {
        requireNonNegative(id, "id");
        Binding current = requireContract();
        return current.contract().getOrder(id).sendAsync().thenApply(order -> Map.of(
                "buyer", order.buyer, "seller", order.seller, "token", order.token,
                "amount", order.amount.toString(), "deadline", order.deadline.toString(),
                "status", statusName(order.status), "description", order.description));
    }

    /** Query OrderCreated, not orderCount/getOrder loops. Never return a partial result on RPC error. */
    public List<Map<String, String>> listOrders() throws IOException {
        Binding current = requireContract();
        var head = web3j.ethBlockNumber().send();
        if (head.hasError()) throw new IOException("eth_blockNumber: " + head.getError().getMessage());
        BigInteger latest = head.getBlockNumber();
        if (current.deployBlock().compareTo(latest) > 0) {
            throw new IllegalArgumentException("fromBlock is ahead of the chain head");
        }
        List<Log> logs = new ArrayList<>();
        for (BigInteger from = current.deployBlock(); from.compareTo(latest) <= 0;
             from = from.add(LOG_BATCH_SIZE)) {
            BigInteger to = from.add(LOG_BATCH_SIZE).subtract(BigInteger.ONE).min(latest);
            EthFilter filter = new EthFilter(DefaultBlockParameter.valueOf(from),
                    DefaultBlockParameter.valueOf(to), current.address());
            filter.addSingleTopic(EventEncoder.encode(Escrow.ORDERCREATED_EVENT));
            var response = web3j.ethGetLogs(filter).send();
            if (response.hasError()) {
                throw new IOException("eth_getLogs " + from + ".." + to + ": "
                        + response.getError().getMessage());
            }
            if (response.getLogs() == null) throw new IOException("eth_getLogs returned no result");
            for (var entry : response.getLogs()) {
                Log log = (Log) entry.get();
                if (!log.isRemoved()) logs.add(log);
            }
        }
        logs.sort(Comparator.comparing(Log::getBlockNumber)
                .thenComparing(Log::getTransactionIndex).thenComparing(Log::getLogIndex));
        return logs.stream().map(log -> {
            Escrow.OrderCreatedEventResponse event = Escrow.getOrderCreatedEventFromLog(log);
            return Map.of("id", event.id.toString(), "buyer", event.buyer, "seller", event.seller,
                    "amount", event.amount.toString(), "blockNumber", log.getBlockNumber().toString(),
                    "transactionHash", log.getTransactionHash());
        }).toList();
    }

    /** Keep role/state enforcement on-chain, including the required wrong-status failure case. */
    public CompletableFuture<TransactionReceipt> resolve(BigInteger id, boolean sellerWins) {
        requireNonNegative(id, "id");
        Binding current = requireContract();
        return CompletableFuture.supplyAsync(() -> {
            synchronized (writeLock) {
                try {
                    requireChain();
                    return current.contract().resolve(id, sellerWins).send();
                } catch (Exception e) {
                    // Preserve TransactionException and its failed receipt for the Controller/report.
                    throw new CompletionException(e);
                }
            }
        });
    }

    private void requireChain() throws IOException {
        var response = web3j.ethChainId().send();
        if (response.hasError()) throw new IOException("eth_chainId: " + response.getError().getMessage());
        if (!response.getChainId().equals(BigInteger.valueOf(chainId))) {
            throw new IllegalStateException("RPC chain ID differs from WEB3J_CHAIN_ID");
        }
    }

    private Binding requireContract() {
        Binding current = binding;
        if (current == null) throw new IllegalStateException("Contract not deployed or loaded; call /deploy or /load first");
        return current;
    }

    private static void requireNonNegative(BigInteger value, String name) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException(name + " must be nonnegative");
    }

    private static String statusName(BigInteger status) {
        int index = status.intValueExact();
        if (index < 0 || index >= STATUS.length) throw new IllegalStateException("Unsupported Escrow status: " + status);
        return STATUS[index];
    }
}
