const state = { node: "", lookback: 5, autoRefresh: true, timer: null, page: "explorer", adminToken: "" };
const $ = (selector) => document.querySelector(selector);
const parameter = (name, label, value = "", required = false, options = null) => ({ name, label, value, required, options });
const ADDRESS = parameter("address", "Address", "0x0000000000000000000000000000000000000000", true);
const TX_HASH = parameter("txhash", "Transaction hash", "0x".padEnd(66, "0"), true);
const TAG = parameter("tag", "Block tag", "latest");
const API_ENDPOINTS = [
  { group: "Official compatible", name: "account.balance", description: "Native balance in decimal wei.", fixed: { module: "account", action: "balance" }, params: [ADDRESS, TAG] },
  { group: "Official compatible", name: "account.balancemulti", description: "Native balances for up to 20 comma-separated addresses.", fixed: { module: "account", action: "balancemulti" }, params: [parameter("address", "Addresses", ADDRESS.value, true), TAG] },
  { group: "Official compatible", name: "account.eth_get_balance", description: "Balance with a JSON-RPC response envelope.", fixed: { module: "account", action: "eth_get_balance" }, params: [ADDRESS, parameter("block", "Block", "latest")] },
  { group: "Official compatible", name: "account.txlist", description: "Indexed top-level transactions for an allowlisted contract.", fixed: { module: "account", action: "txlist" }, params: [ADDRESS, parameter("startblock", "Start block", "0"), parameter("endblock", "End block", "latest"), parameter("page", "Page", "1"), parameter("offset", "Page size", "25"), parameter("sort", "Sort", "desc", false, ["desc", "asc"])] },
  { group: "Official compatible", name: "block.eth_block_number", description: "Current Besu block height.", fixed: { module: "block", action: "eth_block_number" }, params: [] },
  { group: "Official compatible", name: "transaction.gettxreceiptstatus", description: "Receipt status in Etherscan format.", fixed: { module: "transaction", action: "gettxreceiptstatus" }, params: [TX_HASH] },
  { group: "Official compatible", name: "transaction.getstatus", description: "Execution error status in Etherscan format.", fixed: { module: "transaction", action: "getstatus" }, params: [TX_HASH] },
  { group: "Official compatible", name: "logs.getLogs", description: "Search only events already indexed in SQLite.", fixed: { module: "logs", action: "getLogs" }, params: [ADDRESS, parameter("fromBlock", "From block", "0"), parameter("toBlock", "To block", "latest"), parameter("topic0", "Topic 0"), parameter("page", "Page", "1"), parameter("offset", "Page size", "100"), parameter("sort", "Sort", "desc", false, ["desc", "asc"])] },
  { group: "Indexer discovery", name: "logs.getIndexedContracts", description: "List configured contract indexes and progress.", fixed: { module: "logs", action: "getIndexedContracts" }, params: [] },
  { group: "Proxy extensions", name: "proxy.eth_chainId", description: "Chain ID used for transaction signing.", fixed: { module: "proxy", action: "eth_chainId" }, params: [] },
  { group: "Proxy extensions", name: "proxy.eth_blockNumber", description: "Current block height.", fixed: { module: "proxy", action: "eth_blockNumber" }, params: [] },
  { group: "Proxy extensions", name: "proxy.eth_getBalance", description: "Native balance in hex wei.", fixed: { module: "proxy", action: "eth_getBalance" }, params: [ADDRESS, TAG] },
  { group: "Proxy extensions", name: "proxy.eth_getTransactionCount", description: "Wallet nonce; use pending before signing.", fixed: { module: "proxy", action: "eth_getTransactionCount" }, params: [ADDRESS, parameter("tag", "Block tag", "pending")] },
  { group: "Proxy extensions", name: "proxy.eth_gasPrice", description: "Legacy gas price suggestion.", fixed: { module: "proxy", action: "eth_gasPrice" }, params: [] },
  { group: "Proxy extensions", name: "proxy.eth_maxPriorityFeePerGas", description: "EIP-1559 priority fee suggestion.", fixed: { module: "proxy", action: "eth_maxPriorityFeePerGas" }, params: [] },
  { group: "Proxy extensions", name: "proxy.eth_feeHistory", description: "Base-fee and priority-fee history.", fixed: { module: "proxy", action: "eth_feeHistory" }, params: [parameter("blockCount", "Block count", "10"), parameter("newestBlock", "Newest block", "latest"), parameter("rewardPercentiles", "Reward percentiles", "10,50,90")] },
  { group: "Proxy extensions", name: "proxy.eth_estimateGas", description: "Estimate a transaction or deployment.", fixed: { module: "proxy", action: "eth_estimateGas" }, params: [parameter("from", "From address", ADDRESS.value), parameter("to", "To address"), parameter("data", "Call data", "0x6000", true), parameter("value", "Value", "0x0"), parameter("tag", "Block tag", "pending")] },
  { group: "Proxy extensions", name: "proxy.eth_call", description: "Simulate read-only EVM execution.", fixed: { module: "proxy", action: "eth_call" }, params: [parameter("from", "From address", ADDRESS.value), parameter("to", "To address"), parameter("data", "Call data", "0x", true), parameter("value", "Value", "0x0"), TAG] },
  { group: "Proxy extensions", name: "proxy.eth_getCode", description: "Get deployed bytecode.", fixed: { module: "proxy", action: "eth_getCode" }, params: [ADDRESS, TAG] },
  { group: "Proxy extensions", name: "proxy.eth_getTransactionByHash", description: "Get a transaction by hash.", fixed: { module: "proxy", action: "eth_getTransactionByHash" }, params: [TX_HASH] },
  { group: "Proxy extensions", name: "proxy.eth_getTransactionReceipt", description: "Get receipt, status, logs, and gas usage.", fixed: { module: "proxy", action: "eth_getTransactionReceipt" }, params: [TX_HASH] },
  { group: "Proxy extensions", name: "proxy.eth_getBlockByNumber", description: "Get block by number or tag.", fixed: { module: "proxy", action: "eth_getBlockByNumber" }, params: [parameter("tag", "Block", "latest", true), parameter("boolean", "Full transactions", "true", false, ["true", "false"])] },
  { group: "Proxy extensions", name: "proxy.eth_getBlockByHash", description: "Get block by hash.", fixed: { module: "proxy", action: "eth_getBlockByHash" }, params: [parameter("blockhash", "Block hash", "0x".padEnd(66, "0"), true), parameter("boolean", "Full transactions", "true", false, ["true", "false"])] },
  { group: "Infrastructure", name: "Health", description: "Combined service and Besu readiness.", path: "/api/health", params: [] },
  { group: "Infrastructure", name: "Liveness", description: "Process liveness without an RPC call.", path: "/api/health/liveness", params: [] },
  { group: "Infrastructure", name: "Readiness", description: "Besu chain ID and block-height readiness.", path: "/api/health/readiness", params: [] },
  { group: "Documentation", name: "OpenAPI document", description: "Machine-readable OpenAPI 3.0 JSON.", path: "/openapi.json", params: [] },
];

async function getJson(path) {
  const response = await fetch(path, { headers: { Accept: "application/json" } });
  let body;
  try { body = await response.json(); } catch (_) { throw new Error(`Invalid JSON response (${response.status})`); }
  if (!response.ok) throw new Error(body.message || `Request failed (${response.status})`);
  return body;
}

function setAlert(selector, error) {
  const element = $(selector);
  if (!error) { element.classList.add("hidden"); element.textContent = ""; return; }
  element.textContent = error instanceof Error ? error.message : String(error);
  element.classList.remove("hidden");
}

function setLoading(table, columns, text = "Loading…") {
  const row = document.createElement("tr");
  const cell = document.createElement("td");
  cell.colSpan = columns;
  cell.className = "table-message";
  cell.textContent = text;
  row.append(cell);
  table.replaceChildren(row);
}

function short(value, left = 9, right = 6) {
  if (!value) return "—";
  const text = String(value);
  return text.length > left + right + 3 ? `${text.slice(0, left)}…${text.slice(-right)}` : text;
}

function quantity(value) {
  if (typeof value === "number") return value;
  if (typeof value === "string" && /^0x[0-9a-f]+$/i.test(value)) return Number.parseInt(value, 16);
  if (typeof value === "string" && /^\d+$/.test(value)) return Number.parseInt(value, 10);
  return null;
}

function numberText(value) {
  const parsed = quantity(value);
  return parsed === null || !Number.isFinite(parsed) ? "—" : parsed.toLocaleString();
}

function exactQuantity(value) {
  if (typeof value === "bigint") return value;
  if (typeof value === "number" && Number.isSafeInteger(value) && value >= 0) return BigInt(value);
  if (typeof value === "string" && /^0x[0-9a-f]+$/i.test(value)) return BigInt(value);
  if (typeof value === "string" && /^\d+$/.test(value)) return BigInt(value);
  return null;
}

function exactNumberText(value) {
  const parsed = exactQuantity(value);
  return parsed === null ? "—" : parsed.toLocaleString();
}

function formatUnits(value, decimals) {
  const amount = exactQuantity(value);
  if (amount === null) return "—";
  const scale = 10n ** BigInt(decimals);
  const whole = amount / scale;
  const fraction = (amount % scale).toString().padStart(decimals, "0").replace(/0+$/, "").slice(0, 8);
  return `${whole.toLocaleString()}${fraction ? `.${fraction}` : ""}`;
}

function relativeTime(hexTimestamp) {
  const timestamp = quantity(hexTimestamp);
  if (timestamp === null) return "—";
  const seconds = Math.max(0, Math.floor(Date.now() / 1000 - timestamp));
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  return `${Math.floor(seconds / 3600)}h ago`;
}

function linkButton(text, title, value, type) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "text-link mono";
  button.textContent = text;
  button.title = title;
  button.addEventListener("click", () => showDetails(type, title, value));
  return button;
}

function showDetails(type, title, value) {
  $("#detail-type").textContent = type;
  $("#detail-title").textContent = title;
  $("#detail-content").textContent = JSON.stringify(value, null, 2);
  $("#detail-tabs").classList.add("hidden");
  $("#detail-overview").classList.add("hidden");
  $("#detail-content").classList.remove("hidden");
  $("#detail-dialog").showModal();
}

function detailField(label, value, hint = "", wide = false) {
  const field = document.createElement("div");
  field.className = `detail-field${wide ? " wide" : ""}`;
  const name = document.createElement("span"); name.textContent = label;
  const content = document.createElement("strong"); content.textContent = value ?? "—";
  field.append(name, content);
  if (hint) { const secondary = document.createElement("small"); secondary.textContent = hint; field.append(secondary); }
  return field;
}

function showTransactionDetails(hash, payload) {
  const tx = payload.transaction || {};
  const receipt = payload.receipt || {};
  const block = payload.block || {};
  const timestamp = exactQuantity(block.timestamp ?? tx.blockTimestamp);
  const date = timestamp === null ? null : new Date(Number(timestamp * 1000n));
  const gasPrice = receipt.effectiveGasPrice ?? tx.gasPrice ?? tx.maxFeePerGas;
  const gasUsed = receipt.gasUsed;
  const totalFee = exactQuantity(gasPrice) !== null && exactQuantity(gasUsed) !== null
    ? exactQuantity(gasPrice) * exactQuantity(gasUsed) : null;
  const latestBlock = exactQuantity(payload.latestBlock);
  const transactionBlock = exactQuantity(tx.blockNumber);
  const input = tx.input || "0x";
  const fields = [
    detailField("Status", receipt.status === "0x1" ? "Success" : receipt.status === "0x0" ? "Failed" : "Pending"),
    detailField("Block", exactNumberText(tx.blockNumber), tx.blockNumber || ""),
    detailField("Timestamp", timestamp === null ? "—" : `${timestamp.toLocaleString()} Unix seconds`, date ? `${date.toLocaleString()} · ${date.toISOString()}` : ""),
    detailField("Confirmations", transactionBlock === null || latestBlock === null ? "Pending" : exactNumberText(latestBlock - transactionBlock + 1n)),
    detailField("From", tx.from || "—", "", true),
    detailField("To / created contract", tx.to || receipt.contractAddress || "Contract creation", "", true),
    detailField("Nonce", exactNumberText(tx.nonce), tx.nonce || ""),
    detailField("Transaction index", exactNumberText(tx.transactionIndex), tx.transactionIndex || ""),
    detailField("Chain ID", exactNumberText(tx.chainId), tx.chainId || ""),
    detailField("Type", exactNumberText(tx.type), tx.type || ""),
    detailField("Gas limit", exactNumberText(tx.gas), tx.gas || ""),
    detailField("Gas used", exactNumberText(gasUsed), gasUsed || ""),
    detailField("Gas price", `${exactNumberText(gasPrice)} wei`, `${formatUnits(gasPrice, 9)} Gwei`),
    detailField("Max fee per gas", `${exactNumberText(tx.maxFeePerGas)} wei`, `${formatUnits(tx.maxFeePerGas, 9)} Gwei`),
    detailField("Priority fee", `${exactNumberText(tx.maxPriorityFeePerGas)} wei`, `${formatUnits(tx.maxPriorityFeePerGas, 9)} Gwei`),
    detailField("Total transaction fee", totalFee === null ? "—" : `${totalFee.toLocaleString()} wei`, totalFee === null ? "" : `${formatUnits(totalFee, 18)} native`),
    detailField("Value", `${exactNumberText(tx.value)} wei`, `${formatUnits(tx.value, 18)} native`),
    detailField("Input data", input.length > 180 ? `${input.slice(0, 180)}… (${Math.max(0, (input.length - 2) / 2).toLocaleString()} bytes)` : input, "", true),
  ];
  $("#detail-type").textContent = "Transaction details";
  $("#detail-title").textContent = hash;
  $("#detail-overview").replaceChildren(...fields);
  $("#detail-content").textContent = JSON.stringify(payload, null, 2);
  $("#detail-tabs").classList.remove("hidden");
  selectDetailTab("overview");
  $("#detail-dialog").showModal();
}

function selectDetailTab(tab) {
  document.querySelectorAll("[data-detail-tab]").forEach((button) => button.classList.toggle("active", button.dataset.detailTab === tab));
  $("#detail-overview").classList.toggle("hidden", tab !== "overview");
  $("#detail-content").classList.toggle("hidden", tab !== "raw");
}

async function loadTransactionDetails(hash) {
  try {
    const payload = await getJson(endpoint("/internal/ui/transaction", { node: state.node, hash }));
    showTransactionDetails(hash, payload);
  } catch (error) { toast(error.message, true); }
}

function transactionLink(text, hash) {
  const button = document.createElement("button");
  button.type = "button"; button.className = "text-link mono"; button.textContent = text; button.title = hash;
  button.addEventListener("click", () => loadTransactionDetails(hash));
  return button;
}

function statusBadge(status) {
  const badge = document.createElement("span");
  badge.className = "status-badge";
  if (status === "0x1") { badge.classList.add("success"); badge.textContent = "Success"; }
  else if (status === "0x0") { badge.classList.add("failed"); badge.textContent = "Failed"; }
  else { badge.classList.add("pending"); badge.textContent = "Pending"; }
  return badge;
}

function endpoint(path, values = {}) {
  const query = new URLSearchParams(values);
  return `${path}?${query}`;
}

async function loadConfiguration() {
  const config = await getJson("/internal/ui/config");
  const select = $("#node-select");
  select.replaceChildren(...config.nodes.map((node) => {
    const option = document.createElement("option");
    option.value = node.name;
    option.textContent = node.name;
    option.title = node.rpcUrl;
    return option;
  }));
  state.node = config.defaultNode;
  select.value = state.node;
}

async function refreshExplorer() {
  setAlert("#explorer-alert", null);
  $("#connection-dot").classList.add("loading");
  setLoading($("#blocks-table"), 5);
  setLoading($("#transactions-table"), 6);
  try {
    const data = await getJson(endpoint("/internal/ui/explorer", {
      node: state.node, lookback: state.lookback, transactionLimit: 20,
    }));
    $("#chain-id").textContent = `${quantity(data.chainId)} (${data.chainId})`;
    $("#latest-block").textContent = Number(data.latestBlock).toLocaleString();
    $("#selected-node").textContent = data.node;
    $("#last-updated").textContent = new Date(data.fetchedAt).toLocaleTimeString();
    renderBlocks(data.blocks || []);
    renderTransactions(data.transactions || []);
    refreshIndexerStatus();
    $("#connection-dot").className = "connection-dot connected";
  } catch (error) {
    setAlert("#explorer-alert", error);
    setLoading($("#blocks-table"), 5, "Could not load blocks.");
    setLoading($("#transactions-table"), 6, "Could not load transactions.");
    $("#connection-dot").className = "connection-dot error";
  }
}

function renderBlocks(blocks) {
  $("#block-count").textContent = `${blocks.length} block${blocks.length === 1 ? "" : "s"}`;
  if (!blocks.length) { setLoading($("#blocks-table"), 5, "No blocks returned."); return; }
  $("#blocks-table").replaceChildren(...blocks.map((block) => {
    const row = document.createElement("tr");
    const blockCell = document.createElement("td");
    blockCell.append(linkButton(numberText(block.number), block.hash || "Block", block, "Block details"));
    const age = document.createElement("td"); age.textContent = relativeTime(block.timestamp);
    const txs = document.createElement("td"); txs.textContent = Array.isArray(block.transactions) ? block.transactions.length : 0;
    const validator = document.createElement("td"); validator.className = "mono"; validator.textContent = short(block.miner || block.author);
    const gas = document.createElement("td"); gas.textContent = numberText(block.gasUsed);
    row.append(blockCell, age, txs, validator, gas);
    return row;
  }));
}

function renderTransactions(transactions) {
  $("#transaction-count").textContent = `${transactions.length} transaction${transactions.length === 1 ? "" : "s"}`;
  if (!transactions.length) { setLoading($("#transactions-table"), 6, "No transactions in the selected blocks."); return; }
  $("#transactions-table").replaceChildren(...transactions.map((tx) => {
    const row = document.createElement("tr");
    const hash = document.createElement("td"); hash.append(transactionLink(short(tx.hash), tx.hash));
    const block = document.createElement("td"); block.textContent = numberText(tx.blockNumber);
    const from = document.createElement("td"); from.className = "mono"; from.textContent = short(tx.from);
    const to = document.createElement("td"); to.className = "mono"; to.textContent = short(tx.to || tx.contractAddress || "Contract creation");
    const status = document.createElement("td"); status.append(statusBadge(tx.receiptStatus));
    const gas = document.createElement("td"); gas.textContent = numberText(tx.gasUsed);
    row.append(hash, block, from, to, status, gas);
    return row;
  }));
}

function countTxpool(value) {
  if (Array.isArray(value)) return value.length;
  if (value && typeof value === "object") return Object.values(value).reduce((sum, entry) => sum + countTxpool(entry), 0);
  return 0;
}

async function refreshNodes() {
  setAlert("#nodes-alert", null);
  try {
    const data = await getJson(endpoint("/internal/ui/nodes/status", { node: state.node }));
    $("#node-connection").textContent = "Connected";
    $("#node-height").textContent = Number(data.blockNumber).toLocaleString();
    $("#node-peer-count").textContent = data.peerCount === null ? "Unavailable" : Number(data.peerCount).toLocaleString();
    $("#node-txpool-count").textContent = data.txpool === null ? "Unavailable" : countTxpool(data.txpool).toLocaleString();
    renderNodeInfo(data);
    renderPeers(Array.isArray(data.peers) ? data.peers : []);
  } catch (error) {
    $("#node-connection").textContent = "Disconnected";
    setAlert("#nodes-alert", error);
  }
}

function renderNodeInfo(data) {
  const info = data.nodeInfo || {};
  const fields = [
    ["Configured node", data.node], ["RPC URL", data.rpcUrl], ["Client", info.name],
    ["Node ID", info.id], ["Enode", info.enode], ["Listen address", info.listenAddr],
  ];
  $("#node-info").replaceChildren(...fields.map(([label, value]) => {
    const group = document.createElement("div");
    const term = document.createElement("dt"); term.textContent = label;
    const description = document.createElement("dd"); description.textContent = value || "Unavailable";
    group.append(term, description); return group;
  }));
}

function renderPeers(peers) {
  $("#peer-count").textContent = `${peers.length} peer${peers.length === 1 ? "" : "s"}`;
  if (!peers.length) { $("#peers-list").replaceChildren(message("No peer data available.")); return; }
  $("#peers-list").replaceChildren(...peers.map((peer) => {
    const item = document.createElement("button"); item.type = "button"; item.className = "stack-item";
    const title = document.createElement("strong"); title.textContent = peer.name || short(peer.id) || "Peer";
    const subtitle = document.createElement("span"); subtitle.textContent = peer.network?.remoteAddress || peer.enode || peer.id || "No address";
    item.append(title, subtitle); item.addEventListener("click", () => showDetails("Peer details", title.textContent, peer)); return item;
  }));
}

async function refreshValidators() {
  setAlert("#validators-alert", null);
  try {
    const data = await getJson(endpoint("/internal/ui/validators", { node: state.node }));
    const validators = Array.isArray(data.validators) ? data.validators : [];
    $("#validator-count").textContent = `${validators.length} validator${validators.length === 1 ? "" : "s"}`;
    $("#validators-list").replaceChildren(...validators.map((address, index) => {
      const card = document.createElement("article");
      const number = document.createElement("span"); number.textContent = `Validator ${index + 1}`;
      const value = document.createElement("strong"); value.textContent = address;
      card.append(number, value); return card;
    }));
    if (!validators.length) $("#validators-list").append(message("No validators returned."));
    $("#pending-votes").textContent = data.pendingVotes === null ? "Unavailable: enable the QBFT API on Besu." : JSON.stringify(data.pendingVotes, null, 2);
  } catch (error) { setAlert("#validators-alert", error); }
}

async function refreshContracts() {
  setAlert("#contracts-alert", null);
  setLoading($("#contracts-table"), 7);
  try {
    const body = await getJson("/internal/ui/contracts");
    renderIndexerBanner(body);
    const contracts = Array.isArray(body.contracts) ? body.contracts : [];
    if (!contracts.length) { setLoading($("#contracts-table"), 7, "No tracked contracts. Add one to begin indexing."); return; }
    $("#contracts-table").replaceChildren(...contracts.map((contract) => {
      const row = document.createElement("tr");
      const identity = document.createElement("td");
      const name = document.createElement("strong"); name.className = "contract-name"; name.textContent = contract.name;
      const address = document.createElement("span"); address.className = "contract-address mono"; address.textContent = contract.address;
      identity.append(name, address);
      const status = document.createElement("td"); status.append(indexerStateBadge(contract.enabled ? contract.state : "paused"));
      const progress = document.createElement("td");
      const track = document.createElement("div"); track.className = "progress-track";
      const fill = document.createElement("span"); fill.style.width = `${Math.max(0, Math.min(100, Number(contract.progress || 0)))}%`; track.append(fill);
      const progressLabel = document.createElement("span"); progressLabel.className = "progress-label"; progressLabel.textContent = `${Number(contract.progress || 0).toFixed(1)}%`;
      progress.append(track, progressLabel);
      const blocks = document.createElement("td"); blocks.textContent = `${exactNumberText(contract.startBlock)} / ${exactNumberText(contract.nextBlock)}`;
      const eventsCount = document.createElement("td"); eventsCount.textContent = exactNumberText(contract.eventCount || 0);
      const transactionCount = document.createElement("td"); transactionCount.textContent = exactNumberText(contract.transactionCount || 0);
      const action = document.createElement("td"); action.className = "row-actions";
      const events = document.createElement("button"); events.type = "button"; events.className = "text-link"; events.textContent = "Events";
      events.addEventListener("click", () => { const form = $("#event-search"); form.elements.address.value = contract.address; form.elements.topic0.value = contract.topics?.[0] || ""; form.scrollIntoView({ behavior: "smooth" }); });
      const transactions = document.createElement("button"); transactions.type = "button"; transactions.className = "text-link"; transactions.textContent = "Transactions";
      transactions.addEventListener("click", () => { const form = $("#contract-transactions-search"); form.elements.address.value = contract.address; form.scrollIntoView({ behavior: "smooth" }); form.requestSubmit(); });
      const toggle = document.createElement("button"); toggle.type = "button"; toggle.className = "text-link"; toggle.textContent = contract.enabled ? "Pause" : "Resume";
      toggle.addEventListener("click", () => mutateContract(`/internal/ui/contracts/${contract.address}`, "PATCH", { enabled: !contract.enabled }));
      const reindex = document.createElement("button"); reindex.type = "button"; reindex.className = "text-link"; reindex.textContent = "Reindex";
      reindex.addEventListener("click", () => reindexContract(contract));
      const remove = document.createElement("button"); remove.type = "button"; remove.className = "text-link"; remove.textContent = "Remove";
      remove.addEventListener("click", () => removeContract(contract));
      action.append(events, transactions, toggle, reindex, remove);
      row.append(identity, status, progress, blocks, eventsCount, transactionCount, action); return row;
    }));
  } catch (error) { setAlert("#contracts-alert", error); setLoading($("#contracts-table"), 7, "Could not load indexes."); }
}

function indexerStateBadge(stateValue) {
  const badge = document.createElement("span");
  const normalized = stateValue || "initializing";
  badge.className = `status-badge ${normalized === "caught_up" ? "success" : normalized === "error" ? "failed" : "pending"}`;
  badge.textContent = normalized.replaceAll("_", " ");
  return badge;
}

async function refreshIndexerStatus() {
  try { renderIndexerBanner(await getJson("/internal/ui/indexer/status")); }
  catch (_) { /* Explorer RPC data remains usable while the indexer status is unavailable. */ }
}

function renderIndexerBanner(status) {
  const text = status.state === "caught_up"
    ? `Indexer caught up at block ${exactNumberText(status.safeHead)}. SQLite data is ephemeral and will rebuild after restart.`
    : status.state === "error"
      ? `Indexer error: ${status.lastError || "see contract status"}`
      : `Indexer ${String(status.state || "initializing").replaceAll("_", " ")} · safe head ${exactNumberText(status.safeHead)}. Historical results may be incomplete.`;
  [$("#explorer-indexer-banner"), $("#contracts-indexer-banner")].forEach((banner) => {
    banner.textContent = text;
    banner.className = `sync-banner ${status.state === "caught_up" ? "caught-up" : status.state === "error" ? "error" : ""}`;
  });
}

async function mutateContract(path, method, body, mayRequestToken = true) {
  try {
    const headers = { Accept: "application/json", "Content-Type": "application/json" };
    if (state.adminToken) headers["X-Contract-Admin-Token"] = state.adminToken;
    const response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    const result = await response.json();
    if (response.status === 401 && mayRequestToken) {
      const token = window.prompt("Contract administration token:");
      if (token === null) throw new Error("Administration token is required");
      state.adminToken = token;
      return mutateContract(path, method, body, false);
    }
    if (!response.ok) throw new Error(result.message || `Request failed (${response.status})`);
    toast("Contract configuration updated");
    await refreshContracts();
    return result;
  } catch (error) { toast(error.message, true); throw error; }
}

async function submitContract(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const values = Object.fromEntries(new FormData(form));
  state.adminToken = values.adminToken || state.adminToken;
  const topics = values.topics.trim() ? values.topics.split(",").map((topic) => topic.trim()).filter(Boolean) : [];
  try {
    await mutateContract("/internal/ui/contracts", "POST", {
      name: values.name.trim(),
      address: values.address.trim(),
      startBlock: Number(values.startBlock),
      topics,
    });
    $("#contract-dialog").close();
    form.reset();
    form.elements.startBlock.value = "0";
  } catch (_) { /* Error toast is shown by mutateContract. */ }
}

async function reindexContract(contract) {
  const raw = window.prompt(`Reindex ${contract.name} from block:`, String(contract.startBlock));
  if (raw === null) return;
  if (!/^\d+$/.test(raw)) { toast("Start block must be a non-negative integer", true); return; }
  await mutateContract(`/internal/ui/contracts/${contract.address}/reindex`, "POST", { startBlock: Number(raw) });
}

async function removeContract(contract) {
  if (!window.confirm(`Stop tracking ${contract.name}? Indexed RAM data will be kept until restart unless you also purge it.`)) return;
  const purge = window.confirm("Also purge this contract's currently indexed events and transactions?");
  await mutateContract(`/internal/ui/contracts/${contract.address}?purge=${purge}`, "DELETE");
}

async function searchContractTransactions(event) {
  event.preventDefault();
  setLoading($("#contract-transactions-table"), 8);
  const values = Object.fromEntries(new FormData(event.currentTarget));
  const query = { module: "account", action: "txlist", address: values.address.trim(), startblock: values.startblock.trim(), endblock: values.endblock.trim(), page: 1, offset: 100, sort: values.sort };
  try {
    const body = await getJson(endpoint("/api", query));
    const transactions = Array.isArray(body.result) ? body.result : [];
    $("#contract-transaction-count").textContent = `${transactions.length} transaction${transactions.length === 1 ? "" : "s"}`;
    if (!transactions.length) { setLoading($("#contract-transactions-table"), 8, typeof body.result === "string" ? body.result : "No transactions found."); return; }
    $("#contract-transactions-table").replaceChildren(...transactions.map((transaction) => {
      const row = document.createElement("tr");
      const hash = document.createElement("td"); hash.className = "mono"; hash.textContent = short(transaction.hash);
      const block = document.createElement("td"); block.textContent = numberText(transaction.blockNumber);
      const age = document.createElement("td"); age.textContent = relativeTime(transaction.timeStamp);
      const from = document.createElement("td"); from.className = "mono"; from.textContent = short(transaction.from);
      const to = document.createElement("td"); to.className = "mono"; to.textContent = short(transaction.to || transaction.contractAddress || "Contract creation");
      const status = document.createElement("td"); status.append(statusBadge(transaction.txreceipt_status === "1" ? "0x1" : "0x0"));
      const gas = document.createElement("td"); gas.textContent = numberText(transaction.gasUsed);
      const action = document.createElement("td"); action.append(transactionLink("Details", transaction.hash));
      row.append(hash, block, age, from, to, status, gas, action); return row;
    }));
  } catch (error) { setLoading($("#contract-transactions-table"), 8, error.message); }
}

async function searchEvents(event) {
  event.preventDefault();
  setLoading($("#events-table"), 5);
  const values = Object.fromEntries(new FormData(event.currentTarget));
  const query = { module: "logs", action: "getLogs", address: values.address.trim(), fromBlock: values.fromBlock.trim(), toBlock: values.toBlock.trim(), page: 1, offset: 100, sort: "desc" };
  if (values.topic0.trim()) query.topic0 = values.topic0.trim();
  try {
    const body = await getJson(endpoint("/api", query));
    const logs = Array.isArray(body.result) ? body.result : [];
    if (!logs.length) { setLoading($("#events-table"), 5, typeof body.result === "string" ? body.result : "No events found."); return; }
    $("#events-table").replaceChildren(...logs.map((log) => {
      const row = document.createElement("tr");
      const block = document.createElement("td"); block.textContent = numberText(log.blockNumber);
      const transaction = document.createElement("td"); transaction.className = "mono"; transaction.textContent = short(log.transactionHash);
      const index = document.createElement("td"); index.textContent = numberText(log.logIndex);
      const topic = document.createElement("td"); topic.className = "mono"; topic.textContent = short(log.topics?.[0] || "—", 12, 8);
      const action = document.createElement("td"); action.append(linkButton("Details", log.transactionHash || "Event", log, "Event details"));
      row.append(block, transaction, index, topic, action); return row;
    }));
  } catch (error) { setLoading($("#events-table"), 5, error.message); }
}

async function searchBlock(event) {
  event.preventDefault();
  const value = $("#block-query").value.trim();
  const isHash = /^0x[0-9a-f]{64}$/i.test(value);
  const query = { module: "proxy", action: isHash ? "eth_getBlockByHash" : "eth_getBlockByNumber", boolean: "true" };
  query[isHash ? "blockhash" : "tag"] = value;
  await searchPublic(endpoint("/api", query), "Block details", value);
}

async function searchTransaction(event) {
  event.preventDefault();
  const hash = $("#transaction-query").value.trim();
  await loadTransactionDetails(hash);
}

async function searchPublic(path, type, title) {
  try {
    const body = await getJson(path);
    if (body.error) throw new Error(body.error.message || "RPC request failed");
    showDetails(type, title, body.result);
  } catch (error) { toast(error.message, true); }
}

function initializeConsole() {
  const select = $("#console-endpoint");
  const groups = new Map();
  API_ENDPOINTS.forEach((definition, index) => {
    let group = groups.get(definition.group);
    if (!group) {
      group = document.createElement("optgroup");
      group.label = definition.group;
      groups.set(definition.group, group);
      select.append(group);
    }
    const option = document.createElement("option");
    option.value = String(index);
    option.textContent = definition.name;
    group.append(option);
  });
  select.addEventListener("change", renderConsoleFields);
  renderConsoleFields();
}

function renderConsoleFields() {
  const definition = API_ENDPOINTS[Number($("#console-endpoint").value || 0)];
  $("#console-description").textContent = definition.description;
  const fields = definition.params.map((field) => {
    const label = document.createElement("label");
    label.textContent = `${field.label}${field.required ? " *" : ""}`;
    let control;
    if (field.options) {
      control = document.createElement("select");
      control.append(...field.options.map((value) => {
        const option = document.createElement("option"); option.value = value; option.textContent = value; return option;
      }));
      control.value = field.value;
    } else {
      control = document.createElement("input");
      control.value = field.value;
      control.placeholder = field.required ? "Required" : "Optional";
    }
    control.name = field.name;
    control.required = field.required;
    control.addEventListener("input", updateConsoleUrl);
    control.addEventListener("change", updateConsoleUrl);
    label.append(control);
    return label;
  });
  $("#console-fields").replaceChildren(...fields);
  if (!fields.length) $("#console-fields").append(message("This endpoint has no parameters."));
  updateConsoleUrl();
}

function consoleRequestUrl() {
  const definition = API_ENDPOINTS[Number($("#console-endpoint").value || 0)];
  if (definition.path) return definition.path;
  const values = { ...definition.fixed };
  new FormData($("#console-form")).forEach((value, name) => {
    if (String(value).trim()) values[name] = String(value).trim();
  });
  return endpoint("/api", values);
}

function updateConsoleUrl() {
  $("#console-url").textContent = `${location.origin}${consoleRequestUrl()}`;
}

async function sendConsoleRequest(event) {
  event.preventDefault();
  const path = consoleRequestUrl();
  const started = performance.now();
  $("#console-response-status").textContent = "Loading";
  $("#console-response-body").textContent = "Sending request…";
  try {
    const response = await fetch(path, { headers: { Accept: "application/json" } });
    const text = await response.text();
    let body;
    try { body = JSON.parse(text); } catch (_) { body = text; }
    $("#console-response-status").textContent = `${response.status} ${response.ok ? "OK" : "Error"}`;
    $("#console-response-status").className = `pill ${response.ok ? "response-ok" : "response-error"}`;
    $("#console-response-meta").textContent = `GET · ${Math.round(performance.now() - started)} ms · ${new Date().toLocaleTimeString()}`;
    $("#console-response-body").textContent = typeof body === "string" ? body : JSON.stringify(body, null, 2);
  } catch (error) {
    $("#console-response-status").textContent = "Failed";
    $("#console-response-status").className = "pill response-error";
    $("#console-response-meta").textContent = "Network request failed";
    $("#console-response-body").textContent = error.message;
  }
}

function message(text) { const element = document.createElement("p"); element.className = "table-message"; element.textContent = text; return element; }
function toast(text, failed = false) { const element = $("#toast"); element.textContent = text; element.className = `toast visible${failed ? " failed" : ""}`; window.setTimeout(() => element.classList.remove("visible"), 3500); }

function navigate(page) {
  const known = [...document.querySelectorAll("[data-page]")].some((element) => element.dataset.page === page);
  state.page = known ? page : "explorer";
  document.querySelectorAll("[data-page]").forEach((element) => element.classList.toggle("active", element.dataset.page === state.page));
  document.querySelectorAll("[data-page-target]").forEach((element) => element.classList.toggle("active", element.dataset.pageTarget === state.page));
  if (state.page === "nodes") refreshNodes();
  if (state.page === "validators") refreshValidators();
  if (state.page === "contracts") refreshContracts();
}

function scheduleRefresh() {
  window.clearInterval(state.timer);
  state.timer = state.autoRefresh ? window.setInterval(() => {
    if (document.hidden) return;
    if (state.page === "explorer") refreshExplorer();
    if (state.page === "contracts") refreshContracts();
  }, 15000) : null;
}

document.querySelectorAll("[data-page-target]").forEach((link) => link.addEventListener("click", () => navigate(link.dataset.pageTarget)));
window.addEventListener("hashchange", () => navigate(location.hash.slice(1)));
$("#node-select").addEventListener("change", (event) => { state.node = event.target.value; refreshExplorer(); if (state.page === "nodes") refreshNodes(); if (state.page === "validators") refreshValidators(); });
$("#lookback").addEventListener("change", (event) => { state.lookback = Number(event.target.value); refreshExplorer(); });
$("#refresh-explorer").addEventListener("click", refreshExplorer);
$("#refresh-nodes").addEventListener("click", refreshNodes);
$("#refresh-validators").addEventListener("click", refreshValidators);
$("#refresh-contracts").addEventListener("click", refreshContracts);
$("#add-contract").addEventListener("click", () => $("#contract-dialog").showModal());
$("#add-contract-form").addEventListener("submit", submitContract);
$("#close-contract-dialog").addEventListener("click", () => $("#contract-dialog").close());
$("#cancel-add-contract").addEventListener("click", () => $("#contract-dialog").close());
$("#toggle-refresh").addEventListener("click", (event) => { state.autoRefresh = !state.autoRefresh; event.target.textContent = state.autoRefresh ? "Pause auto-refresh" : "Resume auto-refresh"; scheduleRefresh(); });
$("#block-search").addEventListener("submit", searchBlock);
$("#transaction-search").addEventListener("submit", searchTransaction);
$("#event-search").addEventListener("submit", searchEvents);
$("#contract-transactions-search").addEventListener("submit", searchContractTransactions);
$("#console-form").addEventListener("submit", sendConsoleRequest);
$("#close-dialog").addEventListener("click", () => $("#detail-dialog").close());
$("#detail-dialog").addEventListener("click", (event) => { if (event.target === event.currentTarget) event.currentTarget.close(); });
document.querySelectorAll("[data-detail-tab]").forEach((button) => button.addEventListener("click", () => selectDetailTab(button.dataset.detailTab)));

(async function initialize() {
  initializeConsole();
  try { await loadConfiguration(); await refreshExplorer(); scheduleRefresh(); navigate(location.hash.slice(1) || "explorer"); }
  catch (error) { setAlert("#explorer-alert", error); $("#connection-dot").className = "connection-dot error"; }
})();
