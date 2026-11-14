-- Drop tables if resetting
DROP TABLE IF EXISTS disputes, orders, commodities, system_settings, users CASCADE;

-- 1. Users Table (Farmers, Buyers, Drivers, Admins)
CREATE TABLE users (
    user_id SERIAL PRIMARY KEY,
    full_name VARCHAR(100) NOT NULL,
    phone VARCHAR(20) UNIQUE NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('FARMER', 'BUYER', 'DRIVER', 'ADMIN')),
    trust_score INT DEFAULT 100,
    loyalty_points INT DEFAULT 0,
    wallet_balance NUMERIC(12,2) DEFAULT 500.00,
    collateral_balance NUMERIC(12,2) DEFAULT 500.00,
    is_suspended BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2. System Settings (Dynamic Petrol Rate & Platform Controls)
CREATE TABLE system_settings (
    setting_key VARCHAR(50) PRIMARY KEY,
    setting_value NUMERIC(10,2) NOT NULL
);

INSERT INTO system_settings (setting_key, setting_value) VALUES ('PETROL_PRICE_GHS', 19.20);

-- 3. Commodities Table (Crops & Livestock)
CREATE TABLE commodities (
    commodity_id SERIAL PRIMARY KEY,
    seller_id INT REFERENCES users(user_id),
    title VARCHAR(100) NOT NULL,
    category VARCHAR(20) NOT NULL CHECK (category IN ('CROP', 'LIVESTOCK')),
    local_unit VARCHAR(50) NOT NULL,
    quantity INT NOT NULL,
    base_price NUMERIC(10,2) NOT NULL,
    quality_grade VARCHAR(15) NOT NULL CHECK (quality_grade IN ('GRADE_A', 'GRADE_B', 'GRADE_C')),
    demand_index NUMERIC(6,2) DEFAULT 10.0,
    supply_index NUMERIC(6,2) DEFAULT 10.0,
    final_unit_price NUMERIC(10,2) NOT NULL,
    image_url TEXT NOT NULL,
    is_available BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 4. Orders & Escrow State Machine Table
CREATE TABLE orders (
    order_id SERIAL PRIMARY KEY,
    buyer_id INT REFERENCES users(user_id),
    commodity_id INT REFERENCES commodities(commodity_id),
    driver_id INT REFERENCES users(user_id),
    quantity_ordered INT NOT NULL,
    product_cost NUMERIC(12,2) NOT NULL,
    transport_fee NUMERIC(12,2) NOT NULL,
    vehicle_type VARCHAR(30) NOT NULL,
    distance_km NUMERIC(6,2) NOT NULL,
    pickup_photo_url TEXT,
    rejection_photo_url TEXT,
    driver_lat NUMERIC(10,6) DEFAULT 5.105350,
    driver_lng NUMERIC(10,6) DEFAULT -1.246600,
    escrow_status VARCHAR(40) DEFAULT 'ESCROW_LOCKED',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 5. Disputes Arbitration Table
CREATE TABLE disputes (
    dispute_id SERIAL PRIMARY KEY,
    order_id INT REFERENCES orders(order_id),
    reason TEXT NOT NULL,
    ruling VARCHAR(30) DEFAULT 'PENDING',
    resolved_at TIMESTAMP
);

-- Seed Initial Demo Accounts for Instant Testing
INSERT INTO users (full_name, phone, role, trust_score, loyalty_points, wallet_balance, collateral_balance) VALUES
('Kwame Mensah (Abura Farms)', '0241000001', 'FARMER', 115, 240, 1200.00, 0.00),
('Ama Serwaa (UCC Chop Bar)', '0241000002', 'BUYER', 110, 310, 3500.00, 0.00),
('Kofi Boateng (Aboboyaa Express)', '0241000003', 'DRIVER', 112, 190, 450.00, 1000.00),
('System Arbitrator', '0241000000', 'ADMIN', 100, 0, 0.00, 0.00);