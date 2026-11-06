// SPDX-License-Identifier: MIT
pragma solidity >=0.8.0 <0.9.0;

// 只声明我们要用的三个函数。Escrow 通过这个接口调用 ERC20 合约。
interface IERC20 {
    function transfer(address to, uint256 value) external returns (bool);
    function transferFrom(address from, address to, uint256 value) external returns (bool);
    function balanceOf(address account) external view returns (uint256);
}

contract Escrow {
    enum Status { Created, Paid, Completed, Refunded, Disputed }

    struct Order {
        address buyer;
        address seller;
        address token;
        uint256 amount;       // 代币最小单位
        uint256 deadline;     // Unix 时间戳，单位秒
        Status status;
        string description;
    }

    address public arbiter;
    uint256 public orderCount;
    mapping(uint256 => Order) private _orders;

    event OrderCreated(uint256 indexed id, address indexed buyer, address indexed seller, address token, uint256 amount, uint256 deadline);
    event OrderPaid(uint256 indexed id);
    event OrderCompleted(uint256 indexed id);
    event OrderRefunded(uint256 indexed id);
    event OrderDisputed(uint256 indexed id, address by);
    event OrderResolved(uint256 indexed id, bool sellerWins);

    modifier onlyArbiter() {
        require(msg.sender == arbiter, "Escrow: caller is not the arbiter");
        _;
    }

    modifier onlyBuyer(uint256 id) {
        require(msg.sender == _orders[id].buyer, "Escrow: caller is not the buyer");
        _;
    }

    modifier inStatus(uint256 id, Status s) {
        require(_orders[id].status == s, "Escrow: wrong status");
        _;
    }

    constructor(address _arbiter) {
        require(_arbiter != address(0), "Escrow: arbiter is the zero address");
        arbiter = _arbiter;
    }

    function getOrder(uint256 id) public view returns (Order memory) {
        require(id < orderCount, "Escrow: order does not exist");
        return _orders[id];
    }

    // 1. 买家创建订单，编号从 0 开始。
    function createOrder(address seller, address token, uint256 amount, uint256 deadline, string memory description) public returns (uint256) {
        require(seller != address(0), "Escrow: seller is the zero address");
        require(seller != msg.sender, "Escrow: seller is the buyer");
        require(amount > 0, "Escrow: amount is zero");
        require(deadline > block.timestamp, "Escrow: deadline in the past");

        uint256 id = orderCount;
        _orders[id] = Order({
            buyer: msg.sender,
            seller: seller,
            token: token,
            amount: amount,
            deadline: deadline,
            status: Status.Created,
            description: description
        });
        orderCount += 1;
        emit OrderCreated(id, msg.sender, seller, token, amount, deadline);
        return id;
    }

    // 2. 付款前，买家需要在代币合约 approve 本 Escrow 的地址。
    function deposit(uint256 id) public onlyBuyer(id) inStatus(id, Status.Created) {
        Order storage o = _orders[id];
        o.status = Status.Paid;
        require(
            IERC20(o.token).transferFrom(msg.sender, address(this), o.amount),
            "Escrow: transferFrom failed"
        );
        emit OrderPaid(id);
    }

    // 3. 买家确认收货，先改状态再转账；转账失败会回滚整笔交易。
    function confirm(uint256 id) public onlyBuyer(id) inStatus(id, Status.Paid) {
        Order storage o = _orders[id];
        o.status = Status.Completed;
        require(IERC20(o.token).transfer(o.seller, o.amount), "Escrow: transfer failed");
        emit OrderCompleted(id);
    }

    // 4. 必须严格超过 deadline 才能退款。
    function refund(uint256 id) public onlyBuyer(id) inStatus(id, Status.Paid) {
        Order storage o = _orders[id];
        require(block.timestamp > o.deadline, "Escrow: deadline not reached");
        o.status = Status.Refunded;
        require(IERC20(o.token).transfer(o.buyer, o.amount), "Escrow: transfer failed");
        emit OrderRefunded(id);
    }

    // 5. 买卖任一方均可在 Paid 状态发起争议。
    function dispute(uint256 id) public inStatus(id, Status.Paid) {
        Order storage o = _orders[id];
        require(msg.sender == o.buyer || msg.sender == o.seller, "Escrow: caller is not a party");
        o.status = Status.Disputed;
        emit OrderDisputed(id, msg.sender);
    }

    // 6. 仲裁只发出 OrderResolved；sellerWins 决定收款方和最终状态。
    function resolve(uint256 id, bool sellerWins) public onlyArbiter inStatus(id, Status.Disputed) {
        Order storage o = _orders[id];
        if (sellerWins) {
            o.status = Status.Completed;
            require(IERC20(o.token).transfer(o.seller, o.amount), "Escrow: transfer failed");
        } else {
            o.status = Status.Refunded;
            require(IERC20(o.token).transfer(o.buyer, o.amount), "Escrow: transfer failed");
        }
        emit OrderResolved(id, sellerWins);
    }
}
