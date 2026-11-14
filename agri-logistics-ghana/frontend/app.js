// REPLACE THIS URL WITH YOUR RENDER BACKEND URL ONCE DEPLOYED (e.g., https://agri-escrow.onrender.com/api)
const API_BASE = "http://localhost:8080/api";

let currentUser = JSON.parse(localStorage.getItem("agri_user")) || null;
let currentPetrolPrice = 19.20;
let map, driverMarker;

// 1. Looping Explainer Video Dynamic Captions (Short & Repetitive Workflow Story)
const adSteps = [
    "STEP 1: Farmer Snaps Produce & Gets Auto-Price",
    "STEP 2: Buyer Locks Payment Safely in Escrow",
    "STEP 3: Driver Verifies Farm Quality & Delivers Live",
    "STEP 4: Buyer Confirms -> Instant Payout to Farmer & Driver!"
];
let stepIdx = 0;
setInterval(() => {
    stepIdx = (stepIdx + 1) % adSteps.length;
    const captionEl = document.getElementById("adCaption");
    if (captionEl) captionEl.innerText = adSteps[stepIdx];
}, 3000);

// 2. Initialize App on Load
document.addEventListener("DOMContentLoaded", () => {
    initMap();
    if (currentUser) showLoggedInState();
    loadPetrolRate();
    loadCommodities();
    loadOrders();
    loadLeaderboard();
    attachPricePreviewListeners();
});

function initMap() {
    map = L.map("liveMap").setView([5.10535, -1.2466], 12); // Cape Coast, Ghana default
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
        attribution: "&copy; OpenStreetMap"
    }).addTo(map);
    driverMarker = L.marker([5.10535, -1.2466]).addTo(map).bindPopup("Active Driver Transit Pin").openPopup();
}

// 3. Authentication Handler
document.getElementById("authForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const payload = {
        fullName: document.getElementById("authName").value,
        phone: document.getElementById("authPhone").value,
        role: document.getElementById("authRole").value
    };
    const res = await fetch(`${API_BASE}/auth`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
    });
    currentUser = await res.json();
    localStorage.setItem("agri_user", JSON.stringify(currentUser));
    showLoggedInState();
});

function showLoggedInState() {
    document.getElementById("activeUserBadge").classList.remove("d-none");
    document.getElementById("logoutBtn").classList.remove("d-none");
    document.getElementById("userStatsBar").classList.remove("d-none");
    document.getElementById("userStatsBar").classList.add("d-flex");

    document.getElementById("activeUserBadge").innerText = `${currentUser.full_name} (${currentUser.role})`;
    document.getElementById("statUserName").innerText = currentUser.full_name;
    document.getElementById("statUserRole").innerText = currentUser.role;
    document.getElementById("statTrust").innerText = currentUser.trust_score;
    document.getElementById("statLoyalty").innerText = currentUser.loyalty_points;
    document.getElementById("statWallet").innerText = Number(currentUser.wallet_balance).toFixed(2);
    document.getElementById("statCollateral").innerText = Number(currentUser.collateral_balance).toFixed(2);
}

function logoutUser() {
    localStorage.removeItem("agri_user");
    location.reload();
}

// 4. Petrol Price Fetch & Update
async function loadPetrolRate() {
    const res = await fetch(`${API_BASE}/petrol-rate`);
    const data = await res.json();
    currentPetrolPrice = Number(data.petrolPrice);
    document.getElementById("navPetrolRate").innerText = currentPetrolPrice.toFixed(2);
    document.getElementById("adminPetrolInput").value = currentPetrolPrice.toFixed(2);
}

async function savePetrolRate() {
    const val = document.getElementById("adminPetrolInput").value;
    await fetch(`${API_BASE}/petrol-rate`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ petrolPrice: val })
    });
    loadPetrolRate();
    alert("Petrol price updated! All transport rates adjusted automatically.");
}

// 5. Farmer Photo Capture & Live Price Formula Preview
function attachPricePreviewListeners() {
    ["itemBasePrice", "itemGrade", "itemDemand", "itemSupply"].forEach(id => {
        document.getElementById(id).addEventListener("input", updateAutoPricePreview);
    });
    document.getElementById("itemPhotoFile").addEventListener("change", () => {
        // Simulate instant computer-vision quality classification on photo capture
        const grades = ["GRADE_A", "GRADE_B"];
        document.getElementById("itemGrade").value = grades[Math.floor(Math.random() * grades.length)];
        updateAutoPricePreview();
    });
}

function updateAutoPricePreview() {
    const base = parseFloat(document.getElementById("itemBasePrice").value) || 0;
    const grade = document.getElementById("itemGrade").value;
    const demand = parseFloat(document.getElementById("itemDemand").value) || 10;
    const supply = parseFloat(document.getElementById("itemSupply").value) || 10;

    const qFactor = grade === "GRADE_A" ? 1.15 : grade === "GRADE_C" ? 0.80 : 1.00;
    const finalPrice = base * qFactor * (1 + (demand - supply) / supply);
    document.getElementById("previewAutoPrice").innerText = `GHS ${finalPrice.toFixed(2)}`;
}

document.getElementById("commodityForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    if (!currentUser) return alert("Please Sign In as a Farmer first.");

    const fileInput = document.getElementById("itemPhotoFile").files[0];
    const reader = new FileReader();
    reader.onload = async function () {
        const payload = {
            sellerId: currentUser.user_id,
            title: document.getElementById("itemTitle").value,
            category: document.getElementById("itemCategory").value,
            localUnit: document.getElementById("itemUnit").value,
            quantity: document.getElementById("itemQty").value,
            basePrice: document.getElementById("itemBasePrice").value,
            qualityGrade: document.getElementById("itemGrade").value,
            demandIndex: document.getElementById("itemDemand").value,
            supplyIndex: document.getElementById("itemSupply").value,
            imageUrl: reader.result
        };
        await fetch(`${API_BASE}/commodities`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });
        alert("Produce/Livestock listed with automated dynamic price!");
        loadCommodities();
    };
    reader.readAsDataURL(fileInput);
});

// 6. Marketplace & Escrow Checkout
async function loadCommodities() {
    const res = await fetch(`${API_BASE}/commodities`);
    const items = await res.json();
    const grid = document.getElementById("commodityGrid");
    grid.innerHTML = items.map(item => `
        <div class="col-md-4">
            <div class="card h-100 border-0 shadow-sm rounded-4 overflow-hidden">
                <img src="${item.image_url}" class="card-img-top" style="height: 190px; object-fit: cover;">
                <div class="card-body">
                    <div class="d-flex justify-content-between mb-1">
                        <span class="badge bg-success">${item.category}</span>
                        <span class="badge bg-dark">Trust: ${item.seller_trust} pts</span>
                    </div>
                    <h5 class="fw-bold mb-1">${item.title}</h5>
                    <p class="small text-muted mb-2">Seller: ${item.seller_name} | Unit: ${item.local_unit}</p>
                    <h5 class="text-success fw-bold">GHS ${Number(item.final_unit_price).toFixed(2)} <small class="text-muted fs-6">/ ${item.local_unit}</small></h5>
                    <p class="small mb-3">Available: <strong>${item.quantity}</strong> | Grade: <strong>${item.quality_grade}</strong></p>
                    <button class="btn btn-success w-100 fw-bold" onclick="openBuyModal(${item.commodity_id}, '${item.title}', ${item.final_unit_price})">
                        Buy with Escrow Protection
                    </button>
                </div>
            </div>
        </div>
    `).join("");
}

function openBuyModal(id, title, unitPrice) {
    if (!currentUser) return alert("Please Sign In first.");
    document.getElementById("buyCommodityId").value = id;
    document.getElementById("buyUnitPrice").value = unitPrice;
    document.getElementById("buyItemTitle").innerText = `${title} (GHS ${unitPrice} per unit)`;
    recalcCheckout();
    new bootstrap.Modal(document.getElementById("buyModal")).show();
}

function recalcCheckout() {
    const unitPrice = parseFloat(document.getElementById("buyUnitPrice").value);
    const qty = parseInt(document.getElementById("buyQty").value) || 1;
    const vehicle = document.getElementById("buyVehicle").value;
    const dist = parseFloat(document.getElementById("buyDistance").value) || 1;

    const productCost = unitPrice * qty;
    const baseRate = vehicle === "KIA_TRUCK" ? 150 : 40;
    const burnRate = vehicle === "KIA_TRUCK" ? 0.25 : 0.08;
    const transportFee = baseRate + (dist * burnRate * currentPetrolPrice);

    document.getElementById("checkoutSummary").innerHTML = `
        Product Cost: <strong>GHS ${productCost.toFixed(2)}</strong><br>
        Transport Fee (${dist} km @ GHS ${currentPetrolPrice}/L): <strong>GHS ${transportFee.toFixed(2)}</strong><br>
        <hr class="my-1">
        Total Escrow Lock: <strong class="text-success">GHS ${(productCost + transportFee).toFixed(2)}</strong>
    `;
}

async function submitEscrowOrder() {
    const payload = {
        buyerId: currentUser.user_id,
        commodityId: document.getElementById("buyCommodityId").value,
        quantityOrdered: document.getElementById("buyQty").value,
        vehicleType: document.getElementById("buyVehicle").value,
        distanceKm: document.getElementById("buyDistance").value
    };
    await fetch(`${API_BASE}/orders`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
    });
    alert("Order placed! Funds locked in Escrow awaiting Driver farm-gate verification.");
    loadOrders();
    loadCommodities();
}

// 7. Orders, Driver Verification, GPS Ping & Admin Disputes
async function loadOrders() {
    const res = await fetch(`${API_BASE}/orders`);
    const orders = await res.json();
    const container = document.getElementById("ordersListContainer");
    const disputesBox = document.getElementById("disputesContainer");

    container.innerHTML = orders.map(o => `
        <div class="card border-0 shadow-sm rounded-4 p-3 mb-3">
            <div class="d-flex justify-content-between">
                <h6 class="fw-bold">Order #${o.order_id}: ${o.commodity_title} (x${o.quantity_ordered})</h6>
                <span class="badge bg-primary">${o.escrow_status}</span>
            </div>
            <p class="small text-muted mb-2">
                Seller: ${o.seller_name} | Buyer: ${o.buyer_name} | Driver: ${o.driver_name || "Awaiting Driver"}<br>
                Product: <strong>GHS ${o.product_cost}</strong> | Transport: <strong>GHS ${o.transport_fee}</strong>
            </p>
            <div class="d-flex flex-wrap gap-2">
                ${o.escrow_status === "ESCROW_LOCKED" ? `
                    <button class="btn btn-sm btn-warning fw-bold" onclick="driverVerify(${o.order_id}, '${o.listing_photo}')">
                        Driver: Verify Produce & Accept Liability
                    </button>` : ""}
                ${o.escrow_status === "IN_TRANSIT_VERIFIED" ? `
                    <button class="btn btn-sm btn-info text-white fw-bold" onclick="pingDriverGps(${o.order_id})">
                        Driver: Ping Live GPS
                    </button>
                    <button class="btn btn-sm btn-success fw-bold" onclick="confirmDelivery(${o.order_id})">
                        Buyer: Confirm Good & Release Pay
                    </button>
                    <button class="btn btn-sm btn-danger fw-bold" onclick="raiseDispute(${o.order_id})">
                        Buyer: Report Bad Goods
                    </button>` : ""}
            </div>
        </div>
    `).join("");

    // Render Disputed Orders in Admin Console
    const disputed = orders.filter(o => o.escrow_status === "DISPUTED_FROZEN");
    disputesBox.innerHTML = disputed.length === 0
        ? `<div class="alert alert-light border">No active disputes pending arbitration.</div>`
        : disputed.map(d => `
            <div class="card border-danger shadow-sm rounded-4 p-3 mb-3">
                <h6 class="fw-bold text-danger">DISPUTE #${d.order_id} — ${d.commodity_title} (Value: GHS ${d.product_cost})</h6>
                <p class="small">Driver verified good condition at pickup. Buyer reported damaged/bad goods on arrival.</p>
                <div class="d-flex gap-2">
                    <button class="btn btn-danger btn-sm fw-bold" onclick="resolveDispute(${d.order_id}, 'DRIVER_GUILTY')">
                        Rule DRIVER GUILTY (Pay Seller GHS ${d.product_cost} from Driver Bond + Refund Buyer + -25 Driver Trust)
                    </button>
                    <button class="btn btn-dark btn-sm fw-bold" onclick="resolveDispute(${d.order_id}, 'SELLER_GUILTY')">
                        Rule SELLER GUILTY (Refund Buyer + Pay Driver Transport + -25 Seller Trust)
                    </button>
                </div>
            </div>
        `).join("");
}

async function driverVerify(orderId, photoUrl) {
    if (!currentUser) return alert("Sign in as a Driver.");
    await fetch(`${API_BASE}/orders/${orderId}/driver-verify`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ driverId: currentUser.user_id, pickupPhotoUrl: photoUrl })
    });
    alert("Cargo verified! Driver collateral frozen & live transit active.");
    loadOrders();
}

async function pingDriverGps(orderId) {
    const lat = 5.10535 + (Math.random() - 0.5) * 0.02;
    const lng = -1.2466 + (Math.random() - 0.5) * 0.02;
    await fetch(`${API_BASE}/orders/${orderId}/gps`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ lat, lng })
    });
    driverMarker.setLatLng([lat, lng]).bindPopup(`Order #${orderId} Driver Location`).openPopup();
    map.setView([lat, lng], 14);
}

async function confirmDelivery(orderId) {
    await fetch(`${API_BASE}/orders/${orderId}/confirm-delivery`, { method: "POST" });
    alert("Escrow released! Seller and Driver paid + Trust & Loyalty points awarded.");
    loadOrders();
    loadLeaderboard();
}

async function raiseDispute(orderId) {
    const reason = prompt("Describe the issue (e.g., Crushed tomatoes during transit, sick goat):");
    if (!reason) return;
    await fetch(`${API_BASE}/orders/${orderId}/dispute`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ reason })
    });
    alert("Escrow frozen! Dispute sent to Admin Arbitrator.");
    loadOrders();
}

async function resolveDispute(orderId, verdict) {
    await fetch(`${API_BASE}/disputes/${orderId}/resolve`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ verdict })
    });
    alert(`Dispute resolved as ${verdict}. Wallets and Trust Scores updated!`);
    loadOrders();
    loadLeaderboard();
}

// 8. Top Performers Leaderboard
async function loadLeaderboard() {
    const res = await fetch(`${API_BASE}/leaderboard`);
    const users = await res.json();
    document.getElementById("leaderboardBody").innerHTML = users.map((u, idx) => `
        <tr>
            <td class="fw-bold">#${idx + 1}</td>
            <td class="fw-semibold">${u.full_name}</td>
            <td><span class="badge bg-secondary">${u.role}</span></td>
            <td class="fw-bold text-success">${u.trust_score} pts</td>
            <td class="fw-bold text-primary">${u.loyalty_points} pts</td>
            <td>${u.is_suspended ? '<span class="badge bg-danger">SUSPENDED</span>' : '<span class="badge bg-success">ACTIVE</span>'}</td>
        </tr>
    `).join("");
}