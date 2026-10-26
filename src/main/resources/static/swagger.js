const operationsContainer = document.querySelector("#operations");
const statusElement = document.querySelector("#api-status");
const countElement = document.querySelector("#endpoint-count");
const filterElement = document.querySelector("#filter");
const template = document.querySelector("#operation-template");
let operationElements = [];

function schemaText(schema) {
  return JSON.stringify(schema || {}, null, 2);
}

function dereference(spec, value) {
  if (!value || !value.$ref) return value || {};
  return value.$ref.slice(2).split("/").reduce((current, key) => current?.[key], spec) || value;
}

function renderParameters(container, spec, parameters = []) {
  if (!parameters.length) return;
  const heading = document.createElement("h2");
  heading.textContent = "Parameters";
  const table = document.createElement("table");
  table.innerHTML = "<thead><tr><th>Name</th><th>Location</th><th>Required</th><th>Schema / description</th></tr></thead>";
  const body = document.createElement("tbody");
  parameters.map((parameter) => dereference(spec, parameter)).forEach((parameter) => {
    const row = document.createElement("tr");
    const cells = [
      parameter.name,
      parameter.in,
      parameter.required ? "yes" : "no",
      [schemaText(parameter.schema), parameter.description].filter(Boolean).join("\n"),
    ];
    cells.forEach((value, index) => {
      const cell = document.createElement("td");
      cell.textContent = value || "—";
      if (index === 2 && parameter.required) cell.className = "required";
      row.append(cell);
    });
    body.append(row);
  });
  table.append(body);
  container.append(heading, table);
}

function renderRequestBody(container, spec, requestBody) {
  if (!requestBody) return;
  const resolved = dereference(spec, requestBody);
  const schema = resolved.content?.["application/json"]?.schema;
  const heading = document.createElement("h2");
  heading.textContent = `Request body${resolved.required ? " (required)" : ""}`;
  const pre = document.createElement("pre");
  pre.textContent = schemaText(dereference(spec, schema));
  container.append(heading, pre);
}

function renderResponses(container, spec, responses = {}) {
  const heading = document.createElement("h2");
  heading.textContent = "Responses";
  const table = document.createElement("table");
  table.innerHTML = "<thead><tr><th>Status</th><th>Description</th><th>Schema</th></tr></thead>";
  const body = document.createElement("tbody");
  Object.entries(responses).forEach(([code, responseValue]) => {
    const response = dereference(spec, responseValue);
    const schema = response.content?.["application/json"]?.schema;
    const row = document.createElement("tr");
    const codeCell = document.createElement("td");
    const descriptionCell = document.createElement("td");
    const schemaCell = document.createElement("td");
    codeCell.className = "response-code";
    codeCell.textContent = code;
    descriptionCell.textContent = response.description || "—";
    schemaCell.textContent = schema ? schemaText(dereference(spec, schema)) : "—";
    row.append(codeCell, descriptionCell, schemaCell);
    body.append(row);
  });
  table.append(body);
  container.append(heading, table);
}

function renderOperation(spec, path, method, operation) {
  const fragment = template.content.cloneNode(true);
  const article = fragment.querySelector(".operation");
  const toggle = fragment.querySelector(".operation-summary");
  const details = fragment.querySelector(".operation-details");
  const methodElement = fragment.querySelector(".method");
  methodElement.textContent = method.toUpperCase();
  methodElement.classList.add(method);
  fragment.querySelector(".path").textContent = path;
  fragment.querySelector(".summary").textContent = operation.summary || "";
  fragment.querySelector(".description").textContent = operation.description || operation.summary || "";
  renderParameters(fragment.querySelector(".parameters"), spec, operation.parameters);
  renderRequestBody(fragment.querySelector(".request-body"), spec, operation.requestBody);
  renderResponses(fragment.querySelector(".responses"), spec, operation.responses);
  toggle.addEventListener("click", () => {
    const expanded = toggle.getAttribute("aria-expanded") === "true";
    toggle.setAttribute("aria-expanded", String(!expanded));
    details.hidden = expanded;
  });
  article.dataset.search = `${method} ${path} ${operation.summary || ""} ${operation.description || ""}`.toLowerCase();
  return article;
}

function applyFilter() {
  const query = filterElement.value.trim().toLowerCase();
  let visible = 0;
  operationElements.forEach((element) => {
    element.hidden = !element.dataset.search.includes(query);
    if (!element.hidden) visible++;
  });
  countElement.textContent = `${visible} of ${operationElements.length} operations`;
}

async function loadSpec() {
  try {
    const response = await fetch("/openapi.json", { headers: { Accept: "application/json" } });
    if (!response.ok) throw new Error(`OpenAPI request returned HTTP ${response.status}`);
    const spec = await response.json();
    document.querySelector("#api-description").textContent = spec.info?.description || "";
    Object.entries(spec.paths || {}).forEach(([path, pathItem]) => {
      Object.entries(pathItem).forEach(([method, operation]) => {
        if (!["get", "post", "put", "patch", "delete"].includes(method)) return;
        operationsContainer.append(renderOperation(spec, path, method, operation));
      });
    });
    operationElements = [...operationsContainer.querySelectorAll(".operation")];
    statusElement.hidden = true;
    applyFilter();
  } catch (error) {
    statusElement.textContent = `Could not load the OpenAPI document: ${error.message}`;
  }
}

filterElement.addEventListener("input", applyFilter);
loadSpec();
