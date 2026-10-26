const output = document.querySelector("#response-output");
const responseTitle = document.querySelector("#response-title");
const responseStatus = document.querySelector("#response-status");
const defaultView = { output, title: responseTitle, status: responseStatus };
const rpcView = {
  output: document.querySelector("#rpc-response-output"),
  title: document.querySelector("#rpc-response-title"),
  status: document.querySelector("#rpc-response-status"),
  endpoint: document.querySelector("#rpc-response-endpoint"),
};

async function request(path, title, showResponse = true, options = {}, view = defaultView) {
  if (showResponse) {
    view.title.textContent = title;
    view.status.textContent = "Loading…";
    view.status.className = "status loading";
    view.output.textContent = "Contacting the proxy…";
    if (view.endpoint) view.endpoint.textContent = `${options.method || "GET"} ${path}`;
  }

  try {
    const response = await fetch(path, {
      ...options,
      headers: { Accept: "application/json", ...(options.headers || {}) },
    });
    const body = await response.json();
    if (showResponse) {
      view.status.textContent = `${response.status} ${response.ok ? "Success" : "Error"}`;
      view.status.className = `status ${response.ok ? "success" : "error"}`;
      view.output.textContent = JSON.stringify(body, null, 2);
    }
    if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
    return body;
  } catch (error) {
    if (showResponse) {
      view.status.textContent = "Request failed";
      view.status.className = "status error";
      view.output.textContent = error.message;
    }
    throw error;
  }
}

async function refreshNetwork() {
  const container = document.querySelector("#network-summary");
  container.replaceChildren(message("Loading…", "muted"));
  try {
    const [chain, block] = await Promise.all([
      request("/api?module=proxy&action=eth_chainId", "Chain ID", false),
      request("/api?module=block&action=eth_block_number", "Latest block", false),
    ]);
    const cards = [
      ["Network", "Private Besu"],
      ["Chain ID", Number.parseInt(chain.result, 16)],
      ["Latest block", Number.parseInt(block.result, 16)],
      ["Event index", "SQLite"],
    ].map(([label, value]) => {
      const card = document.createElement("div");
      const caption = document.createElement("span");
      const content = document.createElement("strong");
      caption.textContent = label;
      content.textContent = value;
      card.append(caption, content);
      return card;
    });
    container.replaceChildren(...cards);
  } catch (error) {
    container.replaceChildren(message(error.message, "error-text"));
  }
}

async function refreshBlocks() {
  const table = document.querySelector("#recent-blocks");
  table.innerHTML = '<tr><td colspan="4" class="muted">Loading…</td></tr>';
  try {
    const head = await request("/api?module=block&action=eth_block_number", "Latest block", false);
    const latest = Number.parseInt(head.result, 16);
    const response = await Promise.all(Array.from({ length: Math.min(10, latest + 1) }, (_, index) =>
      request(`/api?module=proxy&action=eth_getBlockByNumber&tag=0x${(latest - index).toString(16)}&boolean=false`, "Recent block", false),
    ));
    table.innerHTML = response.map(({ result: block }) => `
      <tr>
        <td><button class="link-button" data-block="${Number.parseInt(block.number, 16)}">${Number.parseInt(block.number, 16)}</button></td>
        <td>${new Date(Number.parseInt(block.timestamp, 16) * 1000).toISOString()}</td>
        <td>${block.transactions.length}</td>
        <td>${Number.parseInt(block.gasUsed, 16)}</td>
      </tr>`).join("");
    table.querySelectorAll("[data-block]").forEach((button) => {
      button.addEventListener("click", () => request(`/api?module=proxy&action=eth_getBlockByNumber&tag=${button.dataset.block}&boolean=true`, `Block ${button.dataset.block}`));
    });
  } catch (error) {
    const row = document.createElement("tr");
    const cell = document.createElement("td");
    cell.colSpan = 4;
    cell.className = "error-text";
    cell.textContent = error.message;
    row.append(cell);
    table.replaceChildren(row);
  }
}

async function refreshIndexedContracts() {
  const table = document.querySelector("#indexed-contracts");
  table.innerHTML = '<tr><td colspan="4" class="muted">Loading…</td></tr>';
  try {
    const response = await request(
      "/api?module=logs&action=getIndexedContracts",
      "Indexed contracts",
      false,
    );
    if (!response.result.length) {
      table.innerHTML = '<tr><td colspan="4" class="muted">No index filters registered yet. Start the indexer first.</td></tr>';
      return;
    }
    table.replaceChildren(...response.result.map((contract) => {
      const row = document.createElement("tr");
      const address = document.createElement("td");
      const topic = document.createElement("td");
      const range = document.createElement("td");
      const action = document.createElement("td");
      const button = document.createElement("button");
      address.textContent = contract.address;
      topic.textContent = contract.topic0 || "All topics";
      range.textContent = `${contract.startBlock} – ${contract.latestIndexedBlock ?? "waiting"}`;
      button.type = "button";
      button.className = "link-button";
      button.textContent = "Search events";
      button.addEventListener("click", () => {
        document.querySelector("#logs-address").value = contract.address;
        document.querySelector("#logs-topic0").value = contract.topic0 || "";
        document.querySelector("#logs-form").scrollIntoView({ behavior: "smooth", block: "center" });
      });
      action.append(button);
      row.append(address, topic, range, action);
      return row;
    }));
  } catch (error) {
    const row = document.createElement("tr");
    const cell = document.createElement("td");
    cell.colSpan = 4;
    cell.className = "error-text";
    cell.textContent = error.message;
    row.append(cell);
    table.replaceChildren(row);
  }
}

function message(text, className) {
  const paragraph = document.createElement("p");
  paragraph.className = className;
  paragraph.textContent = text;
  return paragraph;
}

document.querySelectorAll("form[data-endpoint]").forEach((form) => {
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const value = new FormData(form).get("value").trim();
    const endpoint = form.dataset.endpoint;
    let path;
    if (endpoint === "blocks") {
      const action = /^0x[0-9a-fA-F]{64}$/.test(value) ? "eth_getBlockByHash" : "eth_getBlockByNumber";
      const parameter = action === "eth_getBlockByHash" ? "blockhash" : "tag";
      path = `/api?module=proxy&action=${action}&${parameter}=${encodeURIComponent(value)}&boolean=true`;
    } else if (endpoint === "transactions") {
      path = `/api?module=proxy&action=eth_getTransactionByHash&txhash=${encodeURIComponent(value)}`;
    } else {
      path = `/api?module=account&action=balance&address=${encodeURIComponent(value)}&tag=latest`;
    }
    request(path, `${endpoint.slice(0, -1)} ${value}`);
  });
});

document.querySelector("#refresh-network").addEventListener("click", refreshNetwork);
document.querySelector("#refresh-blocks").addEventListener("click", refreshBlocks);
document.querySelector("#refresh-indexed-contracts").addEventListener("click", refreshIndexedContracts);

document.querySelector("#logs-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = new FormData(event.currentTarget);
  const query = new URLSearchParams({
    module: "logs",
    action: "getLogs",
    address: form.get("address").trim(),
    fromBlock: form.get("fromBlock").trim(),
    toBlock: "latest",
    page: "1",
    offset: "100",
    sort: "desc",
  });
  const topic0 = form.get("topic0").trim();
  if (topic0) query.set("topic0", topic0);
  await request(`/api?${query}`, "Indexed contract logs");
});

document.querySelectorAll("[data-get]").forEach((button) => {
  button.addEventListener("click", async () => {
    button.disabled = true;
    try {
      await request(button.dataset.get, button.textContent.trim(), true, {}, rpcView);
    } catch (error) {
      // The request helper renders the error beside the controls.
    } finally {
      button.disabled = false;
    }
  });
});

document.querySelector("#nonce-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = event.currentTarget.querySelector("button[type='submit']");
  const address = new FormData(event.currentTarget).get("address").trim();
  button.disabled = true;
  try {
    await request(
      `/api?module=proxy&action=eth_getTransactionCount&address=${encodeURIComponent(address)}&tag=pending`,
      "Pending wallet nonce",
      true,
      {},
      rpcView,
    );
  } catch (error) {
    // The request helper renders the error beside the controls.
  } finally {
    button.disabled = false;
  }
});

async function submitTransactionRpc(path, title) {
  await request(path, title, true, {
    method: "GET",
  }, rpcView);
}

document.querySelector("#estimate-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = event.currentTarget.querySelector("button[type='submit']");
  button.disabled = true;
  try {
    const form = new FormData(event.currentTarget);
    const query = new URLSearchParams({
      module: "proxy",
      action: "eth_estimateGas",
      from: form.get("from").trim(),
      data: form.get("data").trim(),
      value: form.get("value").trim(),
      tag: "pending",
    });
    await submitTransactionRpc(`/api?${query}`, "Estimated deployment gas");
  } catch (error) {
    // The request helper renders the error beside the controls.
  } finally {
    button.disabled = false;
  }
});

document.querySelector("#simulate-call").addEventListener("click", async (event) => {
  event.currentTarget.disabled = true;
  try {
    const form = new FormData(document.querySelector("#estimate-form"));
    const query = new URLSearchParams({
      module: "proxy",
      action: "eth_call",
      from: form.get("from").trim(),
      data: form.get("data").trim(),
      value: form.get("value").trim(),
      tag: "pending",
    });
    await submitTransactionRpc(`/api?${query}`, "Deployment simulation");
  } catch (error) {
    // The request helper renders the error beside the controls.
  } finally {
    event.currentTarget.disabled = false;
  }
});

refreshNetwork();
refreshBlocks();
refreshIndexedContracts();
