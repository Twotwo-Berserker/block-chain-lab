// Disposable development chain: no predefined private keys are stored or printed.
module.exports = {
  networks: {
    hardhat: {
      chainId: 31337,
      accounts: [],
      throwOnTransactionFailures: false,
      throwOnCallFailures: true,
    },
  },
};
