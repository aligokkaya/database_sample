-- ============================================================
-- Test Database 3: E-Ticaret Platformu (KafeinShop)
-- 200+ satır veri, tricky kolon isimleri, karışık PII senaryoları
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ── 1. CATEGORIES (PII yok) ──────────────────────────────────
CREATE TABLE categories (
    cat_id      SERIAL PRIMARY KEY,
    cat_name    VARCHAR(100) NOT NULL,
    slug        VARCHAR(100) UNIQUE,
    description TEXT,
    parent_id   INTEGER,
    is_active   BOOLEAN DEFAULT TRUE,
    sort_order  INTEGER DEFAULT 0
);

-- ── 2. PRODUCTS (PII yok) ────────────────────────────────────
CREATE TABLE products (
    product_id  SERIAL PRIMARY KEY,
    sku         VARCHAR(50) UNIQUE NOT NULL,
    title       VARCHAR(200) NOT NULL,
    description TEXT,
    price       NUMERIC(10,2),
    cost_price  NUMERIC(10,2),
    cat_id      INTEGER REFERENCES categories(cat_id),
    brand       VARCHAR(100),
    stock_qty   INTEGER DEFAULT 0,
    weight_kg   NUMERIC(5,2),
    is_active   BOOLEAN DEFAULT TRUE,
    created_at  TIMESTAMP DEFAULT NOW()
);

-- ── 3. CUSTOMERS (PII var — tricky kolon isimleri) ───────────
CREATE TABLE customers (
    cust_id       SERIAL PRIMARY KEY,
    cust_uuid     UUID DEFAULT uuid_generate_v4(),
    fname         VARCHAR(100),           -- PII: first_name
    lname         VARCHAR(100),           -- PII: last_name
    email_addr    VARCHAR(255) UNIQUE,    -- PII: email_address
    cell_phone    VARCHAR(20),            -- PII: phone_number
    birth_dt      DATE,                   -- PII: date_of_birth
    identity_num  VARCHAR(11),            -- PII: tckn (tricky isim)
    gender_code   CHAR(1),
    loyalty_pts   INTEGER DEFAULT 0,
    acct_status   VARCHAR(20) DEFAULT 'active',
    registered_dt TIMESTAMP DEFAULT NOW()
);

-- ── 4. SHIPPING_ADDRESSES (PII var — kişisel adres) ──────────
CREATE TABLE shipping_addresses (
    addr_id        SERIAL PRIMARY KEY,
    cust_id        INTEGER REFERENCES customers(cust_id),
    addr_alias     VARCHAR(50),
    recipient_name VARCHAR(200),          -- PII: full_name
    recipient_phone VARCHAR(20),          -- PII: phone_number
    addr_line1     TEXT,                  -- PII: home_address
    city_name      VARCHAR(100),
    postal_cd      VARCHAR(10),
    latitude       NUMERIC(10,7),         -- PII: koordinat (kişisel)
    longitude      NUMERIC(10,7),         -- PII: koordinat (kişisel)
    is_default     BOOLEAN DEFAULT FALSE
);

-- ── 5. ORDERS ────────────────────────────────────────────────
CREATE TABLE orders (
    order_id      SERIAL PRIMARY KEY,
    order_ref     VARCHAR(30) UNIQUE NOT NULL,
    cust_id       INTEGER REFERENCES customers(cust_id),
    addr_id       INTEGER REFERENCES shipping_addresses(addr_id),
    order_dt      TIMESTAMP DEFAULT NOW(),
    subtotal      NUMERIC(10,2),
    shipping_fee  NUMERIC(8,2),
    discount_amt  NUMERIC(8,2) DEFAULT 0,
    grand_total   NUMERIC(10,2),
    order_status  VARCHAR(30),
    delivery_note TEXT         -- Serbest metin: bazı satırlarda PII
);

-- ── 6. ORDER_ITEMS (PII yok) ─────────────────────────────────
CREATE TABLE order_items (
    item_id    SERIAL PRIMARY KEY,
    order_id   INTEGER REFERENCES orders(order_id),
    product_id INTEGER REFERENCES products(product_id),
    qty        INTEGER,
    unit_price NUMERIC(10,2),
    line_total NUMERIC(10,2)
);

-- ── 7. PAYMENT_RECORDS (PII var) ─────────────────────────────
CREATE TABLE payment_records (
    payment_id  SERIAL PRIMARY KEY,
    order_id    INTEGER REFERENCES orders(order_id),
    amount      NUMERIC(10,2),
    currency    CHAR(3) DEFAULT 'TRY',
    pay_method  VARCHAR(30),
    masked_pan  VARCHAR(19),              -- PII: kredi kartı (tricky isim)
    holder_iban VARCHAR(34),              -- PII: iban
    txn_ref     VARCHAR(50),
    pay_status  VARCHAR(20),
    paid_at     TIMESTAMP
);

-- ── 8. EMPLOYEES (PII var — kritik, tricky isimler) ──────────
CREATE TABLE employees (
    emp_id         SERIAL PRIMARY KEY,
    personnel_code VARCHAR(20) UNIQUE,
    name_given     VARCHAR(100),          -- PII: first_name (tricky)
    name_family    VARCHAR(100),          -- PII: last_name (tricky)
    work_email     VARCHAR(255) UNIQUE,   -- PII: email_address
    direct_line    VARCHAR(20),           -- PII: phone_number (tricky)
    birth_dt       DATE,                  -- PII: date_of_birth
    tc_kimlik      VARCHAR(11),           -- PII: tckn
    passport_num   VARCHAR(20),           -- PII: national_id
    ssn_us         VARCHAR(11),           -- PII: SSN (ABD çalışanlar)
    monthly_wage   NUMERIC(10,2),
    hire_dt        DATE,
    department     VARCHAR(100),
    title          VARCHAR(100),
    is_active      BOOLEAN DEFAULT TRUE
);

-- ── 9. PRODUCT_REVIEWS (bazı satırlarda gömülü PII) ──────────
CREATE TABLE product_reviews (
    review_id    SERIAL PRIMARY KEY,
    product_id   INTEGER REFERENCES products(product_id),
    cust_id      INTEGER REFERENCES customers(cust_id),
    star_rating  INTEGER CHECK (star_rating BETWEEN 1 AND 5),
    review_title VARCHAR(200),
    review_body  TEXT,                    -- Bazı satırlarda gömülü PII
    is_verified  BOOLEAN DEFAULT FALSE,
    helpful_cnt  INTEGER DEFAULT 0,
    submitted_dt TIMESTAMP DEFAULT NOW()
);

-- ── 10. ACTIVITY_LOGS (JSONB ile gömülü PII) ─────────────────
CREATE TABLE activity_logs (
    log_id      SERIAL PRIMARY KEY,
    session_ref VARCHAR(50),
    cust_id     INTEGER,
    action_type VARCHAR(50),
    payload     JSONB,                    -- PII: gömülü kişisel veri
    ip_addr     INET,                     -- PII: ip_address
    user_agent  TEXT,
    logged_at   TIMESTAMP DEFAULT NOW()
);

-- ── 11. SUPPORT_TICKETS (bazı satırlarda gömülü PII) ─────────
CREATE TABLE support_tickets (
    ticket_id     SERIAL PRIMARY KEY,
    cust_id       INTEGER REFERENCES customers(cust_id),
    ticket_ref    VARCHAR(20) UNIQUE,
    subject       VARCHAR(200),
    body          TEXT,                   -- Bazı satırlarda gömülü PII
    priority      VARCHAR(10),
    ticket_status VARCHAR(20) DEFAULT 'open',
    created_at    TIMESTAMP DEFAULT NOW(),
    resolved_at   TIMESTAMP
);

-- ═══════════════════════════════════════════════════════════
-- SAMPLE DATA  (toplam 50+ satır her ana tabloda)
-- ═══════════════════════════════════════════════════════════

-- CATEGORIES (8 satır)
INSERT INTO categories (cat_name, slug, description, sort_order) VALUES
('Elektronik',  'elektronik',  'Telefon, bilgisayar, aksesuarlar', 1),
('Giyim',       'giyim',       'Erkek, kadın, çocuk giyim',        2),
('Kitap',       'kitap',       'Roman, bilim, teknik kitaplar',    3),
('Spor',        'spor',        'Spor malzemeleri ve giyim',        4),
('Ev & Yaşam',  'ev-yasam',    'Mobilya, dekorasyon, mutfak',      5),
('Kozmetik',    'kozmetik',    'Makyaj, cilt bakımı, parfüm',      6),
('Oyuncak',     'oyuncak',     'Çocuk oyuncakları ve oyunları',    7),
('Otomotiv',    'otomotiv',    'Araç aksesuarları ve yedek parça', 8);

-- PRODUCTS (15 satır, PII yok)
INSERT INTO products (sku, title, description, price, cost_price, cat_id, brand, stock_qty, weight_kg) VALUES
('ELK001', 'iPhone 15 Pro',        'Apple iPhone 15 Pro 256GB Titanium',       54999.00, 45000.00, 1, 'Apple',         50,  0.19),
('ELK002', 'Samsung Galaxy S24',   'Samsung Galaxy S24 128GB Siyah',           32999.00, 27000.00, 1, 'Samsung',       75,  0.17),
('ELK003', 'MacBook Air M2',       'Apple MacBook Air 13" M2 8GB 256GB',       42999.00, 36000.00, 1, 'Apple',         30,  1.24),
('ELK004', 'AirPods Pro 2',        'Apple AirPods Pro 2. Nesil',                8999.00,  7200.00, 1, 'Apple',        100,  0.06),
('ELK005', 'Xiaomi Redmi Note 13', 'Xiaomi Redmi Note 13 8GB 256GB',            9499.00,  7500.00, 1, 'Xiaomi',       120,  0.17),
('GIY001', 'Nike Air Max 270',     'Nike Air Max 270 Erkek Spor Ayakkabı',      3299.00,  2100.00, 4, 'Nike',         200,  0.45),
('GIY002', 'Adidas Ultraboost 23', 'Adidas Ultraboost 23 Koşu Ayakkabısı',     4599.00,  3000.00, 4, 'Adidas',       150,  0.48),
('KIT001', 'Temiz Kod',            'Robert C. Martin - Clean Code Türkçe',       249.00,   120.00, 3, 'Prentice Hall',300,  0.42),
('KIT002', 'Dune',                 'Frank Herbert - Dune Serisi 1. Kitap',       189.00,    90.00, 3, 'Çitlembik',    250,  0.38),
('KIT003', 'Atomik Alışkanlıklar', 'James Clear - Atomic Habits Türkçe',         219.00,   100.00, 3, 'Optimist',     400,  0.35),
('SPR001', 'Decathlon Yoga Matı',  'Domyos Kaymaz Yoga ve Pilates Matı',         399.00,   180.00, 4, 'Decathlon',    500,  1.20),
('EV001',  'Tefal Air Fryer',      'Tefal Easy Fry Compact 4.2L',               2999.00,  1800.00, 5, 'Tefal',         80,  3.50),
('KOZ001', 'La Roche-Posay SPF50', 'La Roche-Posay Anthelios Güneş Kremi',       699.00,   380.00, 6, 'La Roche-Posay',300, 0.10),
('OYN001', 'LEGO Technic 42154',   'LEGO Technic Ford GT Set',                  1899.00,  1100.00, 7, 'LEGO',          60,  0.75),
('OTO001', 'Bosch Silecek Seti',   'Bosch Aerotwin A927S Silecek Seti',          799.00,   400.00, 8, 'Bosch',        180,  0.55);

-- CUSTOMERS (25 satır, PII var — tricky kolon isimleri)
INSERT INTO customers (fname, lname, email_addr, cell_phone, birth_dt, identity_num, gender_code, loyalty_pts) VALUES
('Ahmet',   'Yılmaz',  'ahmet.yilmaz@gmail.com',     '05321234567', '1985-03-15', '12345678901', 'E', 1250),
('Fatma',   'Kaya',    'fatma.kaya@hotmail.com',      '05449876543', '1990-07-22', '23456789012', 'K',  850),
('Mehmet',  'Demir',   'mehmet.demir@yahoo.com',      '05551112233', '1978-11-08', '34567890123', 'E', 2100),
('Ayşe',    'Çelik',   'ayse.celik@gmail.com',        '05321234568', '1995-04-30', '45678901234', 'K',  430),
('Mustafa', 'Şahin',   'mustafa.sahin@outlook.com',   '05441234567', '1982-09-14', '56789012345', 'E', 3200),
('Zeynep',  'Arslan',  'zeynep.arslan@gmail.com',     '05339876543', '1998-01-27', '67890123456', 'K',  120),
('Ali',     'Koç',     'ali.koc@icloud.com',          '05321234569', '1975-06-03', '78901234567', 'E', 4500),
('Emine',   'Aydın',   'emine.aydin@gmail.com',       '05449876544', '1988-12-19', '89012345678', 'K',  680),
('Hasan',   'Öztürk',  'hasan.ozturk@hotmail.com',    '05551112234', '1970-02-28', '90123456789', 'E',  950),
('Hacer',   'Çetin',   'hacer.cetin@gmail.com',       '05321234570', '1993-08-11', '11223344556', 'K',  210),
('İbrahim', 'Yıldız',  'ibrahim.yildiz@gmail.com',    '05441234568', '1980-05-25', '22334455667', 'E', 1800),
('Hatice',  'Kurt',    'hatice.kurt@yahoo.com',       '05339876544', '1987-10-07', '33445566778', 'K',  760),
('Ömer',    'Polat',   'omer.polat@gmail.com',        '05321234571', '1976-03-18', '44556677889', 'E', 5100),
('Elif',    'Güneş',   'elif.gunes@hotmail.com',      '05449876545', '1994-07-04', '55667788990', 'K',  340),
('Yusuf',   'Avcı',    'yusuf.avci@gmail.com',        '05551112235', '1983-11-22', '66778899001', 'E', 1650),
('Merve',   'Bulut',   'merve.bulut@icloud.com',      '05321234572', '1996-02-14', '77889900112', 'K',  890),
('Kadir',   'Kara',    'kadir.kara@gmail.com',        '05441234569', '1973-09-30', '88990011223', 'E', 2350),
('Seda',    'Doğan',   'seda.dogan@outlook.com',      '05339876545', '1991-04-16', '99001122334', 'K',  420),
('Burak',   'Erdoğan', 'burak.erdogan@gmail.com',     '05321234573', '1986-08-09', '11122233344', 'E', 1100),
('Ceren',   'Yavuz',   'ceren.yavuz@gmail.com',       '05449876546', '1999-12-01', '22233344455', 'K',   55),
('Tarık',   'Aktaş',   'tarik.aktas@hotmail.com',     '05551112236', '1979-06-17', '33344455566', 'E', 3800),
('Gül',     'Öz',      'gul.oz@gmail.com',            '05321234574', '1992-01-23', '44455566677', 'K',  670),
('Serkan',  'Ateş',    'serkan.ates@yahoo.com',       '05441234570', '1984-10-05', '55566677788', 'E', 2900),
('Pınar',   'Güler',   'pinar.guler@gmail.com',       '05339876546', '1997-03-12', '66677788899', 'K',  180),
('Emre',    'Bozkurt', 'emre.bozkurt@gmail.com',      '05321234575', '1981-07-28', '77788899001', 'E', 4200);

-- EMPLOYEES (15 satır, kritik PII — tricky kolon isimleri)
INSERT INTO employees (personnel_code, name_given, name_family, work_email, direct_line, birth_dt, tc_kimlik, passport_num, ssn_us, monthly_wage, hire_dt, department, title) VALUES
('EMP001', 'Deniz',    'Özkan',    'deniz.ozkan@kafeinshop.com',    '02121234567', '1988-04-12', '12312312312', 'U11234567', NULL,          18000, '2019-03-01', 'Mühendislik',   'Kıdemli Yazılım Mühendisi'),
('EMP002', 'Berk',     'Uysal',    'berk.uysal@kafeinshop.com',     '02121234568', '1990-09-25', '23423423423', 'U22345678', NULL,          16500, '2020-06-15', 'Mühendislik',   'Yazılım Mühendisi'),
('EMP003', 'Rana',     'Sevim',    'rana.sevim@kafeinshop.com',     '02121234569', '1985-02-18', '34534534534', NULL,        '123-45-6789', 22000, '2018-01-10', 'Pazarlama',     'Pazarlama Direktörü'),
('EMP004', 'Volkan',   'Keskin',   'volkan.keskin@kafeinshop.com',  '02121234570', '1992-11-07', '45645645645', 'U33456789', NULL,          14000, '2021-09-01', 'Satış',         'Satış Temsilcisi'),
('EMP005', 'Neslihan', 'Tunç',     'neslihan.tunc@kafeinshop.com',  '02121234571', '1987-06-30', '56756756756', NULL,        '234-56-7890', 20000, '2019-07-01', 'İK',            'İK Müdürü'),
('EMP006', 'Oğuz',     'Kılıç',    'oguz.kilic@kafeinshop.com',     '02121234572', '1993-03-14', '67867867867', 'U44567890', NULL,          13500, '2022-02-01', 'Lojistik',      'Depo Sorumlusu'),
('EMP007', 'Selin',    'Acar',     'selin.acar@kafeinshop.com',     '02121234573', '1989-08-22', '78978978978', NULL,        '345-67-8901', 17500, '2020-03-15', 'Finans',        'Muhasebe Uzmanı'),
('EMP008', 'Murat',    'Başaran',  'murat.basaran@kafeinshop.com',  '02121234574', '1986-12-03', '89089089089', 'U55678901', NULL,          25000, '2017-11-01', 'Yönetim',       'GM Yardımcısı'),
('EMP009', 'Filiz',    'Sarı',     'filiz.sari@kafeinshop.com',     '02121234575', '1994-05-19', '90190190190', NULL,        NULL,          12000, '2022-08-01', 'Müşteri Hizm.', 'Müşteri Temsilcisi'),
('EMP010', 'Cenk',     'Yaman',    'cenk.yaman@kafeinshop.com',     '02121234576', '1991-01-08', '11211311411', 'U66789012', NULL,          15000, '2021-04-01', 'Mühendislik',   'DevOps Mühendisi'),
('EMP011', 'Tuba',     'Öztürk',   'tuba.ozturk@kafeinshop.com',    '02121234577', '1983-07-15', '22322422522', NULL,        '456-78-9012', 19000, '2018-09-01', 'Hukuk',         'Hukuk Danışmanı'),
('EMP012', 'Alper',    'Çakır',    'alper.cakir@kafeinshop.com',    '02121234578', '1995-10-28', '33433533633', 'U77890123', NULL,          13000, '2023-01-16', 'Pazarlama',     'Sosyal Medya Uzmanı'),
('EMP013', 'İpek',     'Mutlu',    'ipek.mutlu@kafeinshop.com',     '02121234579', '1988-04-06', '44544644744', NULL,        NULL,          14500, '2020-11-01', 'Tasarım',       'UI/UX Tasarımcısı'),
('EMP014', 'Kemal',    'Demirtaş', 'kemal.demirtas@kafeinshop.com', '02121234580', '1980-09-21', '55655755855', 'U88901234', NULL,          28000, '2016-05-01', 'Yönetim',       'CTO'),
('EMP015', 'Aylin',    'Gürbüz',   'aylin.gurbuz@kafeinshop.com',   '02121234581', '1990-03-17', '66766866966', NULL,        '567-89-0123', 16000, '2019-12-01', 'Veri',          'Veri Analisti');

-- SHIPPING_ADDRESSES (25 satır, kişisel adres — PII)
INSERT INTO shipping_addresses (cust_id, addr_alias, recipient_name, recipient_phone, addr_line1, city_name, postal_cd, latitude, longitude, is_default) VALUES
(1,  'Ev', 'Ahmet Yılmaz',   '05321234567', 'Bağdat Cad. No:45 D:3 Kadıköy',         'İstanbul', '34710', 40.9826, 29.0561, TRUE),
(1,  'İş', 'Ahmet Yılmaz',   '05321234567', 'Levent Mah. Büyükdere Cad. No:100 K:5', 'İstanbul', '34394', 41.0817, 29.0119, FALSE),
(2,  'Ev', 'Fatma Kaya',     '05449876543', 'Çankaya Mah. Atatürk Bul. No:12 D:4',   'Ankara',   '06680', 39.9208, 32.8541, TRUE),
(3,  'Ev', 'Mehmet Demir',   '05551112233', 'Alsancak Mah. Kıbrıs Şehitleri Cad. No:7','İzmir',  '35220', 38.4382, 27.1484, TRUE),
(4,  'Ev', 'Ayşe Çelik',     '05321234568', 'Muratpaşa Mah. Atatürk Cad. No:8 D:2',  'Antalya',  '07030', 36.8969, 30.7133, TRUE),
(5,  'Ev', 'Mustafa Şahin',  '05441234567', 'Nilüfer Mah. Özlüce Sok. No:15',        'Bursa',    '16110', 40.2087, 28.9784, TRUE),
(6,  'Ev', 'Zeynep Arslan',  '05339876543', 'Seyhan Mah. Turhan Cemal Bul. No:33',   'Adana',    '01120', 37.0017, 35.3289, TRUE),
(7,  'Ev', 'Ali Koç',        '05321234569', 'Bornova Mah. Kazımdirik Cad. No:22',     'İzmir',    '35030', 38.4667, 27.2167, TRUE),
(8,  'Ev', 'Emine Aydın',    '05449876544', 'Kızılay Mah. Ziya Gökalp Cad. No:44',   'Ankara',   '06420', 39.9333, 32.8597, TRUE),
(9,  'Ev', 'Hasan Öztürk',   '05551112234', 'Konak Mah. Cumhuriyet Bul. No:55',      'İzmir',    '35250', 38.4189, 27.1287, TRUE),
(10, 'Ev', 'Hacer Çetin',    '05321234570', 'Yenişehir Mah. İnönü Cad. No:33 D:6',   'Mersin',   '33130', 36.8121, 34.6415, TRUE),
(11, 'Ev', 'İbrahim Yıldız', '05441234568', 'Osmangazi Mah. Cumhuriyet Cad. No:18',  'Bursa',    '16010', 40.1885, 29.0610, TRUE),
(12, 'Ev', 'Hatice Kurt',    '05339876544', 'Meram Mah. Nalçacı Cad. No:7 D:3',      'Konya',    '42090', 37.8746, 32.4932, TRUE),
(13, 'Ev', 'Ömer Polat',     '05321234571', 'Şahinbey Mah. İstasyon Cad. No:18',     'Gaziantep','27010', 37.0662, 37.3833, TRUE),
(14, 'Ev', 'Elif Güneş',     '05449876545', 'Selçuklu Mah. Ankara Cad. No:44 D:5',   'Konya',    '42040', 37.9167, 32.4833, TRUE),
(15, 'Ev', 'Yusuf Avcı',     '05551112235', 'Çukurova Mah. Karataş Cad. No:9',       'Adana',    '01010', 37.0000, 35.3213, TRUE),
(16, 'Ev', 'Merve Bulut',    '05321234572', 'Karşıyaka Mah. Girne Cad. No:66 D:4',   'İzmir',    '35600', 38.4578, 27.1122, TRUE),
(17, 'Ev', 'Kadir Kara',     '05441234569', 'Altındağ Mah. Anafartalar Cad. No:9',   'Ankara',   '06050', 39.9400, 32.8700, TRUE),
(18, 'Ev', 'Seda Doğan',     '05339876545', 'Muratpaşa Mah. Güllük Cad. No:3 D:7',   'Antalya',  '07100', 36.8864, 30.7056, TRUE),
(19, 'Ev', 'Burak Erdoğan',  '05321234573', 'Pendik Mah. Bağdat Cad. No:201 D:3',    'İstanbul', '34899', 40.8756, 29.2614, TRUE),
(20, 'Ev', 'Ceren Yavuz',    '05449876546', 'Beşiktaş Mah. Barbaros Bul. No:12',     'İstanbul', '34353', 41.0428, 29.0097, TRUE),
(21, 'Ev', 'Tarık Aktaş',    '05551112236', 'Keçiören Mah. Plevne Cad. No:88 D:2',   'Ankara',   '06280', 39.9784, 32.8500, TRUE),
(22, 'Ev', 'Gül Öz',         '05321234574', 'Buca Mah. Adnan Menderes Bul. No:15',   'İzmir',    '35390', 38.3833, 27.1833, TRUE),
(23, 'Ev', 'Serkan Ateş',    '05441234570', 'Şehitkamil Mah. Suburcu Cad. No:5 D:1', 'Gaziantep','27090', 37.0600, 37.3600, TRUE),
(24, 'Ev', 'Pınar Güler',    '05339876546', 'Etimesgut Mah. Atatürk Bul. No:77',     'Ankara',   '06790', 39.9500, 32.6800, TRUE);

-- ORDERS (30 satır, bazı delivery_note satırlarında gömülü PII)
INSERT INTO orders (order_ref, cust_id, addr_id, subtotal, shipping_fee, discount_amt, grand_total, order_status, delivery_note) VALUES
('ORD-2024-0001', 1,  1,  54999.00, 0.00,   0.00,   54999.00, 'teslim_edildi', 'Kapıda teslim alacak, zili çalın.'),
('ORD-2024-0002', 2,  3,   3299.00, 29.90,  0.00,    3328.90, 'teslim_edildi', NULL),
('ORD-2024-0003', 3,  4,    249.00, 14.90,  0.00,     263.90, 'teslim_edildi', 'Lütfen kapıya bırakın.'),
('ORD-2024-0004', 4,  5,   8999.00, 0.00,  450.00,   8549.00, 'kargoda',       NULL),
('ORD-2024-0005', 5,  6,   4599.00, 29.90,  0.00,    4628.90, 'teslim_edildi', 'Komşuya verebilirsiniz, adım Mustafa Şahin tel: 05441234567'),
('ORD-2024-0006', 6,  7,    399.00, 14.90,  0.00,     413.90, 'teslim_edildi', NULL),
('ORD-2024-0007', 7,  8,  42999.00, 0.00,   0.00,   42999.00, 'teslim_edildi', NULL),
('ORD-2024-0008', 8,  9,    219.00, 14.90,  0.00,     233.90, 'iptal',         NULL),
('ORD-2024-0009', 9,  10,  2999.00, 29.90,  0.00,    3028.90, 'teslim_edildi', 'Eşime teslim edin, adı Ayşe Öztürk, 05551112234'),
('ORD-2024-0010', 10, 11,   699.00, 14.90,  0.00,     713.90, 'teslim_edildi', NULL),
('ORD-2024-0011', 11, 12,  1899.00, 29.90,  0.00,    1928.90, 'kargoda',       NULL),
('ORD-2024-0012', 12, 13,  9499.00, 0.00,   0.00,    9499.00, 'teslim_edildi', NULL),
('ORD-2024-0013', 13, 14,  3299.00, 29.90, 150.00,   3178.90, 'teslim_edildi', NULL),
('ORD-2024-0014', 14, 15,   249.00, 14.90,  0.00,     263.90, 'hazırlanıyor',  'Ulaşamadığınızda arayın: 05449876545 Elif Güneş'),
('ORD-2024-0015', 15, 16, 54999.00, 0.00,   0.00,   54999.00, 'teslim_edildi', NULL),
('ORD-2024-0016', 16, 17,  8999.00, 0.00,   0.00,    8999.00, 'teslim_edildi', NULL),
('ORD-2024-0017', 17, 18,   219.00, 14.90,  0.00,     233.90, 'teslim_edildi', NULL),
('ORD-2024-0018', 18, 19,  4599.00, 29.90,  0.00,    4628.90, 'kargoda',       NULL),
('ORD-2024-0019', 19, 20,  2999.00, 0.00,  300.00,   2699.00, 'teslim_edildi', '{"müşteri": "Burak Erdoğan", "email": "burak.erdogan@gmail.com", "not": "hediye paketi"}'),
('ORD-2024-0020', 20, 21,  1899.00, 29.90,  0.00,    1928.90, 'teslim_edildi', NULL),
('ORD-2024-0021', 21, 22,   699.00, 14.90,  0.00,     713.90, 'hazırlanıyor',  NULL),
('ORD-2024-0022', 22, 23,  9499.00, 0.00,   0.00,    9499.00, 'teslim_edildi', NULL),
('ORD-2024-0023', 23, 24, 42999.00, 0.00,   0.00,   42999.00, 'teslim_edildi', NULL),
('ORD-2024-0024', 24, 25,   399.00, 14.90,  0.00,     413.90, 'kargoda',       NULL),
('ORD-2024-0025', 25, 1,    249.00, 14.90,  0.00,     263.90, 'teslim_edildi', NULL),
('ORD-2024-0026', 1,  2,   8999.00, 0.00,   0.00,    8999.00, 'teslim_edildi', NULL),
('ORD-2024-0027', 3,  4,   3299.00, 29.90,  0.00,    3328.90, 'iptal',         NULL),
('ORD-2024-0028', 5,  6,    699.00, 14.90,  0.00,     713.90, 'teslim_edildi', NULL),
('ORD-2024-0029', 7,  8,   2999.00, 0.00,   0.00,    2999.00, 'kargoda',       '{"customer_name": "Ali Koç", "phone": "05321234569", "note": "kırılgan ürün"}'),
('ORD-2024-0030', 9,  10, 54999.00, 0.00,   0.00,   54999.00, 'teslim_edildi', NULL);

-- ORDER_ITEMS (15 satır)
INSERT INTO order_items (order_id, product_id, qty, unit_price, line_total) VALUES
(1, 1, 1, 54999.00, 54999.00), (2, 6, 1, 3299.00, 3299.00),
(3, 8, 1, 249.00, 249.00),     (4, 4, 1, 8999.00, 8999.00),
(5, 7, 1, 4599.00, 4599.00),   (6, 11, 1, 399.00, 399.00),
(7, 3, 1, 42999.00, 42999.00), (8, 10, 1, 219.00, 219.00),
(9, 12, 1, 2999.00, 2999.00),  (10, 13, 1, 699.00, 699.00),
(11, 14, 1, 1899.00, 1899.00), (12, 5, 1, 9499.00, 9499.00),
(13, 6, 1, 3299.00, 3299.00),  (14, 8, 1, 249.00, 249.00),
(15, 1, 1, 54999.00, 54999.00);

-- PAYMENT_RECORDS (20 satır — masked_pan tricky PII)
INSERT INTO payment_records (order_id, amount, currency, pay_method, masked_pan, holder_iban, txn_ref, pay_status, paid_at) VALUES
(1,  54999.00,'TRY','kredi_karti','4521-****-****-7823','TR330006100519786457841326','TXN001','başarılı','2024-01-15 10:23:45'),
(2,   3328.90,'TRY','kredi_karti','5267-****-****-4412', NULL,                       'TXN002','başarılı','2024-01-15 11:05:22'),
(3,    263.90,'TRY','havale',      NULL,                 'TR230006200372810000006672','TXN003','başarılı','2024-01-15 12:45:10'),
(4,   8549.00,'TRY','kredi_karti','4111-****-****-1111', NULL,                       'TXN004','beklemede','2024-01-16 09:30:00'),
(5,   4628.90,'TRY','banka_karti','9876-****-****-5432', NULL,                       'TXN005','başarılı','2024-01-16 14:22:33'),
(7,  42999.00,'TRY','kredi_karti','5500-****-****-0004','TR920006100519786457841327','TXN007','başarılı','2024-01-17 16:45:55'),
(9,   3028.90,'TRY','kredi_karti','4012-****-****-8888', NULL,                       'TXN009','başarılı','2024-01-18 11:30:20'),
(10,   713.90,'TRY','havale',      NULL,                 'TR610006200372810000006673','TXN010','başarılı','2024-01-18 13:15:44'),
(11,  1928.90,'TRY','kredi_karti','3714-****-****-9631', NULL,                       'TXN011','beklemede','2024-01-19 08:20:00'),
(12,  9499.00,'TRY','banka_karti','6011-****-****-1117','TR140006100519786457841328','TXN012','başarılı','2024-01-19 15:45:30'),
(13,  3178.90,'TRY','kredi_karti','4532-****-****-7777', NULL,                       'TXN013','başarılı','2024-01-20 10:10:10'),
(15, 54999.00,'TRY','kredi_karti','4916-****-****-2222','TR580006100519786457841329','TXN015','başarılı','2024-01-20 17:30:00'),
(16,  8999.00,'TRY','havale',      NULL,                 'TR740006200372810000006674','TXN016','başarılı','2024-01-21 09:00:00'),
(17,   233.90,'TRY','nakit',       NULL,                  NULL,                      'TXN017','başarılı','2024-01-21 11:45:00'),
(18,  4628.90,'TRY','kredi_karti','4539-****-****-3333', NULL,                       'TXN018','beklemede','2024-01-22 08:30:00'),
(19,  2699.00,'TRY','kredi_karti','4556-****-****-4444','TR820006100519786457841330','TXN019','başarılı','2024-01-22 14:20:15'),
(22,  9499.00,'TRY','banka_karti','4929-****-****-5555', NULL,                       'TXN022','başarılı','2024-01-23 10:30:00'),
(23, 42999.00,'TRY','kredi_karti','5425-****-****-6666','TR200006100519786457841331','TXN023','başarılı','2024-01-23 16:00:00'),
(26,  8999.00,'TRY','kredi_karti','4485-****-****-7777','TR960006100519786457841332','TXN026','başarılı','2024-01-24 12:00:00'),
(30, 54999.00,'TRY','kredi_karti','4024-****-****-9999', NULL,                       'TXN030','başarılı','2024-01-24 15:30:00');

-- PRODUCT_REVIEWS (20 satır — bazılarında gömülü PII)
INSERT INTO product_reviews (product_id, cust_id, star_rating, review_title, review_body, is_verified, helpful_cnt) VALUES
(1,  1,  5, 'Harika telefon',         'Çok memnun kaldım, fiyatı yüksek ama değer. Kesinlikle tavsiye ederim.', TRUE,  12),
(1,  7,  4, 'İyi ürün',               'Genel olarak iyi, kamera biraz beklentimin altında kaldı.', TRUE, 5),
(2,  3,  5, 'Süper telefon',          'Samsung her zaman kalitelidir. Bu modeli de beğendim.', TRUE, 8),
(3,  7,  5, 'Mükemmel bilgisayar',    'MacBook Air M2 gerçekten inanılmaz. Pil ömrü muhteşem.', TRUE, 23),
(4,  1,  5, 'AirPods Pro tam isabet', 'Gürültü engelleme özelliği çok başarılı. Kesinlikle alın.', TRUE, 31),
(5,  6,  3, 'İdare eder',             'Fiyatı göz önünde bulundurursak fena değil ama biraz yavaş.', FALSE, 2),
(6,  2,  5, 'Çok rahat',              'Nike kalitesi her zaman farklı. Ayağım hiç şişmedi.', TRUE, 9),
(7,  5,  4, 'Güzel koşu ayakkabısı',  'Adidas kalitesi iyi ama biraz dar geldi, bir numara büyük alın.', TRUE, 7),
(8,  9,  5, 'Harika kitap',           'Robert Martin bu kitapta her şeyi açıklamış. Her yazılımcı okusun.', FALSE, 45),
(9,  12, 4, 'İyi roman',              'Frank Herbert''in dili biraz ağır ama okunmaya değer.', TRUE, 11),
(10, 15, 5, 'Hayat değiştiren kitap', 'Atomik Alışkanlıklar gerçekten işe yarıyor. Tavsiye ederim.', TRUE, 67),
(11, 4,  4, 'Kaliteli mat',           'Kaymıyor, iyi tutuyor. Temizlemesi de kolay.', FALSE, 3),
(12, 8,  5, 'Mükemmel fritöz',        'Tefal her zaman güvenilir. Yağsız pişiriyor gerçekten.', TRUE, 15),
-- Tricky: review_body içinde gömülü email
(13, 10, 5, 'Cilt dostu krem',        'Cildime çok iyi geldi. Sormak isteyenler hacer.cetin@gmail.com adresine yazabilir.', FALSE, 1),
(14, 11, 5, 'LEGO kalitesi',          'LEGO her zaman kalitelidir. Çocuğum çok sevdi.', TRUE, 19),
(6,  4,  2, 'Beklentimi karşılamadı', 'Nike fiyatına göre beklentimi karşılamadı. İade ettim.', TRUE, 4),
-- Tricky: review_body içinde gömülü isim+email
(1,  13, 5, 'Kesinlikle alın',        'Ömer Polat - omer.polat@gmail.com olarak tavsiye ederim.', FALSE, 2),
(3,  17, 4, 'MacBook iyi ama pahalı', 'Performans harika ama fiyatı çok yüksek. Banka hesabım boşaldı :)', TRUE, 8),
-- Tricky: review_body içinde telefon numarası
(5,  20, 1, 'Hayal kırıklığı',        'Telefonum 3 ayda bozuldu. Sorun yaşayanlar: 05449876546 Ceren Yavuz', FALSE, 0),
(8,  22, 5, 'Klasikleşmiş eser',      'Temiz Kod her programcının başucu kitabı olmalı.', TRUE, 33);

-- ACTIVITY_LOGS (15 satır — payload JSONB içinde PII)
INSERT INTO activity_logs (session_ref, cust_id, action_type, payload, ip_addr, user_agent) VALUES
('SES001', 1,  'LOGIN',          '{"email": "ahmet.yilmaz@gmail.com", "device": "iPhone", "success": true}',                                           '192.168.1.101', 'Mozilla/5.0 iPhone'),
('SES002', 2,  'PURCHASE',       '{"customer": "Fatma Kaya", "email": "fatma.kaya@hotmail.com", "order": "ORD-2024-0002", "amount": 3328.90}',           '10.0.0.45',     'Mozilla/5.0 Chrome'),
('SES003', 3,  'PROFILE_UPDATE', '{"old_email": "mdemir@yahoo.com", "new_email": "mehmet.demir@yahoo.com", "phone": "05551112233"}',                    '172.16.0.10',   'Mozilla/5.0 Firefox'),
('SES004', 4,  'LOGIN',          '{"email": "ayse.celik@gmail.com", "device": "Android", "success": true}',                                            '192.168.1.102', 'Mozilla/5.0 Android'),
('SES005', 5,  'PASSWORD_RESET', '{"email": "mustafa.sahin@outlook.com", "reset_token": "abc123xyz"}',                                                  '10.0.0.50',     'Mozilla/5.0 Safari'),
('SES006', 7,  'PURCHASE',       '{"customer": "Ali Koç", "phone": "05321234569", "order": "ORD-2024-0007", "card_last4": "0004"}',                      '192.168.1.103', 'Mozilla/5.0 Chrome'),
('SES007', 9,  'ADDRESS_ADD',    '{"name": "Hasan Öztürk", "phone": "05551112234", "address": "Konak Mah. Cumhuriyet Bul. No:55", "city": "İzmir"}',    '10.0.1.22',     'Mozilla/5.0 Firefox'),
('SES008', 11, 'LOGIN',          '{"email": "ibrahim.yildiz@gmail.com", "tc_no": "22334455667", "success": true}',                                      '192.168.2.55',  'Mozilla/5.0 Chrome'),
('SES009', 13, 'PURCHASE',       '{"customer_name": "Ömer Polat", "email": "omer.polat@gmail.com", "total": 3178.90}',                                  '10.0.2.100',    'Mozilla/5.0 Safari'),
('SES010', 15, 'RETURN_REQUEST', '{"customer": "Yusuf Avcı", "phone": "05551112235", "order": "ORD-2024-0015", "reason": "beklentiyi karşılamadı"}',    '172.16.1.20',   'Mozilla/5.0 Android'),
('SES011', 17, 'PROFILE_UPDATE', '{"old_phone": "05441234568", "new_phone": "05441234569", "email": "kadir.kara@gmail.com"}',                           '192.168.1.104', 'Mozilla/5.0 Chrome'),
('SES012', 19, 'PURCHASE',       '{"customer": "Burak Erdoğan", "email": "burak.erdogan@gmail.com", "iban": "TR330006100519786457841326"}',              '10.0.0.88',     'Mozilla/5.0 Firefox'),
('SES013', 21, 'LOGIN_FAILED',   '{"email": "tarik.aktas@hotmail.com", "reason": "wrong_password", "attempts": 3}',                                     '192.168.3.77',  'Mozilla/5.0 Safari'),
('SES014', 23, 'PURCHASE',       '{"customer": "Serkan Ateş", "email": "serkan.ates@yahoo.com", "card": "4485-****-****-7777", "amount": 42999.00}',     '10.0.3.55',     'Mozilla/5.0 Chrome'),
('SES015', 25, 'LOGOUT',         '{"email": "emre.bozkurt@gmail.com", "session_duration_min": 45, "cart_items": 0}',                                    '172.16.2.30',   'Mozilla/5.0 Firefox');

-- SUPPORT_TICKETS (15 satır — bazılarında gömülü PII)
INSERT INTO support_tickets (cust_id, ticket_ref, subject, body, priority, ticket_status) VALUES
(1,  'TKT-0001', 'Kargo takip',      'Siparişim nerede? Takip numarası çalışmıyor.', 'düşük',  'çözüldü'),
(2,  'TKT-0002', 'İade talebi',      'Ürün hasarlı geldi, iade etmek istiyorum.', 'yüksek', 'açık'),
-- Tricky: body içinde TC kimlik numarası
(3,  'TKT-0003', 'Fatura sorunu',    'Adıma fatura kesilmedi. Mehmet Demir, TC: 34567890123, fatura istiyorum.', 'orta', 'açık'),
(4,  'TKT-0004', 'Ödeme hatası',     'Kartım çekildi ama sipariş oluşmadı.', 'yüksek', 'işlemde'),
(5,  'TKT-0005', 'Ürün sorusu',      'Bu ürün garantili mi?', 'düşük', 'çözüldü'),
(6,  'TKT-0006', 'Kargo gecikmesi',  'Siparişim 5 gündür gelmedi, çok acil.', 'orta', 'açık'),
-- Tricky: body içinde email + telefon
(7,  'TKT-0007', 'Şifre sıfırlama',  'Şifremi unuttum. Ali Koç, ali.koc@icloud.com, 05321234569', 'orta', 'çözüldü'),
(8,  'TKT-0008', 'Yanlış ürün',      'Başka ürün geldi, doğrusunu istiyorum.', 'yüksek', 'işlemde'),
(9,  'TKT-0009', 'İndirim kodu',     'İndirim kodum çalışmıyor.', 'düşük', 'çözüldü'),
-- Tricky: body içinde email + telefon
(10, 'TKT-0010', 'Hesap dondurma',   'Hesabım kilitlendi. hacer.cetin@gmail.com / 05321234570', 'yüksek', 'açık'),
(11, 'TKT-0011', 'Adres değişikliği','Teslimat adresimi değiştirmek istiyorum.', 'düşük', 'çözüldü'),
(12, 'TKT-0012', 'Ürün bilgisi',     'Telefon renk seçenekleri neler?', 'düşük', 'çözüldü'),
(13, 'TKT-0013', 'Çift sipariş',     'Yanlışlıkla iki sipariş oluştu, birini iptal edin.', 'orta', 'işlemde'),
-- Tricky: body JSON formatında PII
(14, 'TKT-0014', 'Kargo adresi',     '{"müşteri": "Elif Güneş", "telefon": "05449876545", "email": "elif.gunes@hotmail.com", "yeni_adres": "Mevlana Cad. No:15 Konya"}', 'orta', 'açık'),
(15, 'TKT-0015', 'Genel öneri',      'Uygulamanız çok kullanışlı, tebrikler!', 'düşük', 'çözüldü');

-- ═══════════════════════════════════════════════════════════
-- EK VERİ — tabloları 50+ satıra tamamla
-- ═══════════════════════════════════════════════════════════

-- CUSTOMERS ek 25 satır (toplam 50)
INSERT INTO customers (fname, lname, email_addr, cell_phone, birth_dt, identity_num, gender_code, loyalty_pts) VALUES
('Kerem',   'Yücel',     'kerem.yucel@gmail.com',       '05321234576', '1989-05-11', '88877766655', 'E',  320),
('Büşra',   'Saraç',     'busra.sarac@hotmail.com',     '05449876547', '1996-09-03', '77766655544', 'K',  150),
('Ufuk',    'Demirci',   'ufuk.demirci@gmail.com',      '05551112237', '1983-02-28', '66655544433', 'E', 2700),
('Şeyma',   'Kaplan',    'seyma.kaplan@yahoo.com',      '05321234577', '1994-11-17', '55544433322', 'K',  490),
('Okan',    'Güven',     'okan.guven@icloud.com',       '05441234571', '1979-07-09', '44433322211', 'E', 3100),
('Dilara',  'Aslantürk', 'dilara.aslanturk@gmail.com',  '05339876547', '1998-04-25', '33322211100', 'K',   80),
('Barış',   'Özdemir',   'baris.ozdemir@outlook.com',   '05321234578', '1986-12-14', '22211100990', 'E', 1420),
('Tuğçe',   'Keskin',    'tugce.keskin@gmail.com',      '05449876548', '1993-06-30', '11100990881', 'K',  760),
('Anıl',    'Çakmak',    'anil.cakmak@gmail.com',       '05551112238', '1981-03-07', '99988877766', 'E', 5400),
('Melis',   'Doğru',     'melis.dogru@hotmail.com',     '05321234579', '1997-10-19', '88877766655', 'K',  230),
('Erhan',   'Bayrak',    'erhan.bayrak@gmail.com',      '05441234572', '1974-08-23', '77766655543', 'E', 4800),
('Sinem',   'Turan',     'sinem.turan@gmail.com',       '05339876548', '1991-01-15', '66655544432', 'K',  610),
('Mert',    'Altun',     'mert.altun@yahoo.com',        '05321234580', '1988-07-04', '55544433321', 'E', 1950),
('Elif',    'Bal',       'elif.bal@gmail.com',          '05449876549', '1995-03-28', '44433322210', 'K',  340),
('Koray',   'Deniz',     'koray.deniz@icloud.com',      '05551112239', '1977-11-12', '33322211109', 'E', 2200),
('Nazlı',   'Tekin',     'nazli.tekin@gmail.com',       '05321234581', '1999-08-06', '22211100998', 'K',   95),
('Furkan',  'Akın',      'furkan.akin@outlook.com',     '05441234573', '1984-05-21', '11100990887', 'E', 3650),
('İrem',    'Şimşek',    'irem.simsek@gmail.com',       '05339876549', '1992-02-09', '99988877765', 'K',  520),
('Cem',     'Özkan',     'cem.ozkan@gmail.com',         '05321234582', '1980-09-17', '88877766654', 'E', 1780),
('Ayça',    'Yılmaz',    'ayca.yilmaz@hotmail.com',     '05449876550', '1996-06-03', '77766655542', 'K',  410),
('Tolga',   'Arslan',    'tolga.arslan@gmail.com',      '05551112240', '1975-04-14', '66655544431', 'E', 5900),
('Derya',   'Kılıç',     'derya.kilic@yahoo.com',       '05321234583', '1990-12-27', '55544433320', 'K',  285),
('Yiğit',   'Cengiz',    'yigit.cengiz@gmail.com',      '05441234574', '1987-10-08', '44433322209', 'E', 2450),
('Gizem',   'Aydoğdu',   'gizem.aydogdu@gmail.com',     '05339876550', '1994-07-22', '33322211108', 'K',  175),
('Ercan',   'Başak',     'ercan.basak@icloud.com',      '05321234584', '1982-03-31', '22211100997', 'E', 3300);

-- SHIPPING_ADDRESSES ek 25 satır (toplam 50)
INSERT INTO shipping_addresses (cust_id, addr_alias, recipient_name, recipient_phone, addr_line1, city_name, postal_cd, latitude, longitude, is_default) VALUES
(26, 'Ev', 'Kerem Yücel',      '05321234576', 'Kadıköy Mah. Moda Cad. No:22',          'İstanbul', '34710', 40.9877, 29.0344, TRUE),
(27, 'Ev', 'Büşra Saraç',     '05449876547', 'Çankaya Mah. Tunalı Hilmi Cad. No:88',  'Ankara',   '06680', 39.9055, 32.8598, TRUE),
(28, 'Ev', 'Ufuk Demirci',    '05551112237', 'Konak Mah. Atatürk Cad. No:15',         'İzmir',    '35250', 38.4237, 27.1428, TRUE),
(29, 'Ev', 'Şeyma Kaplan',    '05321234577', 'Muratpaşa Mah. Güllük Cad. No:7',       'Antalya',  '07030', 36.8882, 30.7012, TRUE),
(30, 'Ev', 'Okan Güven',      '05441234571', 'Osmangazi Mah. İnegöl Cad. No:33',      'Bursa',    '16010', 40.1980, 29.0601, TRUE),
(31, 'Ev', 'Dilara Aslantürk','05339876547', 'Seyhan Mah. Ziyapaşa Bul. No:45',       'Adana',    '01120', 37.0143, 35.3252, TRUE),
(32, 'Ev', 'Barış Özdemir',   '05321234578', 'Bornova Mah. Atatürk Cad. No:65',       'İzmir',    '35030', 38.4700, 27.2200, TRUE),
(33, 'Ev', 'Tuğçe Keskin',    '05449876548', 'Keçiören Mah. Atatürk Bul. No:12',      'Ankara',   '06280', 39.9712, 32.8594, TRUE),
(34, 'Ev', 'Anıl Çakmak',     '05551112238', 'Pendik Mah. Sahil Yolu No:88',          'İstanbul', '34899', 40.8702, 29.2590, TRUE),
(35, 'Ev', 'Melis Doğru',     '05321234579', 'Karşıyaka Mah. Cemal Gürsel Cad. No:5', 'İzmir',   '35600', 38.4601, 27.1158, TRUE),
(36, 'Ev', 'Erhan Bayrak',    '05441234572', 'Altındağ Mah. Hürriyet Cad. No:18',     'Ankara',   '06050', 39.9456, 32.8712, TRUE),
(37, 'Ev', 'Sinem Turan',     '05339876548', 'Şehitkamil Mah. Gazimuhtarpaşa Bul.',   'Gaziantep','27090', 37.0580, 37.3580, TRUE),
(38, 'Ev', 'Mert Altun',      '05321234580', 'Nilüfer Mah. Beşevler Sok. No:3',       'Bursa',    '16110', 40.2101, 28.9750, TRUE),
(39, 'Ev', 'Elif Bal',        '05449876549', 'Selçuklu Mah. Mevlana Cad. No:77',      'Konya',    '42040', 37.9122, 32.4844, TRUE),
(40, 'Ev', 'Koray Deniz',     '05551112239', 'Çukurova Mah. Adana Bul. No:55',        'Adana',    '01010', 37.0033, 35.3244, TRUE),
(41, 'Ev', 'Nazlı Tekin',     '05321234581', 'Beşiktaş Mah. Çırağan Cad. No:10',     'İstanbul', '34353', 41.0488, 29.0022, TRUE),
(42, 'Ev', 'Furkan Akın',     '05441234573', 'Etimesgut Mah. İstanbul Yolu No:14',    'Ankara',   '06790', 39.9534, 32.6744, TRUE),
(43, 'Ev', 'İrem Şimşek',     '05339876549', 'Buca Mah. Ankara Cad. No:99',           'İzmir',    '35390', 38.3900, 27.1900, TRUE),
(44, 'Ev', 'Cem Özkan',       '05321234582', 'Ümraniye Mah. Alemdağ Cad. No:44',      'İstanbul', '34760', 41.0167, 29.1000, TRUE),
(45, 'Ev', 'Ayça Yılmaz',     '05449876550', 'Yenimahalle Mah. Batıkent Bul. No:21',  'Ankara',   '06370', 39.9756, 32.7300, TRUE),
(46, 'Ev', 'Tolga Arslan',    '05551112240', 'Sarıyer Mah. Büyükdere Cad. No:300',    'İstanbul', '34457', 41.1500, 29.0200, TRUE),
(47, 'Ev', 'Derya Kılıç',     '05321234583', 'Meram Mah. Konya Cad. No:67',           'Konya',    '42090', 37.8633, 32.4944, TRUE),
(48, 'Ev', 'Yiğit Cengiz',    '05441234574', 'Şahinbey Mah. Suburcu Cad. No:19',      'Gaziantep','27010', 37.0655, 37.3811, TRUE),
(49, 'Ev', 'Gizem Aydoğdu',   '05339876550', 'Lüleburgaz Mah. Trakya Cad. No:8',     'Kırklareli','39750', 41.4067, 27.3567, TRUE),
(50, 'Ev', 'Ercan Başak',     '05321234584', 'Melikgazi Mah. Talas Cad. No:33',       'Kayseri',  '38030', 38.7322, 35.4853, TRUE);

-- ORDERS ek 21 satır (toplam 51)
INSERT INTO orders (order_ref, cust_id, addr_id, subtotal, shipping_fee, discount_amt, grand_total, order_status, delivery_note) VALUES
('ORD-2024-0031', 26, 26,  9499.00,  0.00,   0.00,  9499.00, 'teslim_edildi', NULL),
('ORD-2024-0032', 27, 27,   249.00, 14.90,   0.00,   263.90, 'kargoda',       NULL),
('ORD-2024-0033', 28, 28,  3299.00, 29.90,   0.00,  3328.90, 'teslim_edildi', 'Apartman görevlisine bırakın.'),
('ORD-2024-0034', 29, 29,  8999.00,  0.00, 450.00,  8549.00, 'hazırlanıyor',  NULL),
('ORD-2024-0035', 30, 30, 54999.00,  0.00,   0.00, 54999.00, 'teslim_edildi', NULL),
('ORD-2024-0036', 31, 31,   399.00, 14.90,   0.00,   413.90, 'teslim_edildi', 'Güvenlik görevlisine verebilirsiniz.'),
('ORD-2024-0037', 32, 32,  4599.00, 29.90,   0.00,  4628.90, 'kargoda',       NULL),
('ORD-2024-0038', 33, 33,  1899.00, 29.90,   0.00,  1928.90, 'teslim_edildi', NULL),
('ORD-2024-0039', 34, 34, 42999.00,  0.00,   0.00, 42999.00, 'teslim_edildi', '{"alıcı": "Anıl Çakmak", "tel": "05551112238", "not": "imzalı teslim"}'),
('ORD-2024-0040', 35, 35,   219.00, 14.90,   0.00,   233.90, 'iptal',         NULL),
('ORD-2024-0041', 36, 36,  2999.00,  0.00,   0.00,  2999.00, 'teslim_edildi', NULL),
('ORD-2024-0042', 37, 37,   699.00, 14.90,   0.00,   713.90, 'kargoda',       NULL),
('ORD-2024-0043', 38, 38,  3299.00, 29.90, 100.00,  3228.90, 'teslim_edildi', 'Acil lazım, lütfen öncelikli gönderin. Mert Altun 05321234580'),
('ORD-2024-0044', 39, 39,   249.00, 14.90,   0.00,   263.90, 'hazırlanıyor',  NULL),
('ORD-2024-0045', 40, 40,  8999.00,  0.00,   0.00,  8999.00, 'teslim_edildi', NULL),
('ORD-2024-0046', 41, 41, 54999.00,  0.00,   0.00, 54999.00, 'teslim_edildi', NULL),
('ORD-2024-0047', 42, 42,  4599.00, 29.90,   0.00,  4628.90, 'kargoda',       NULL),
('ORD-2024-0048', 43, 43,  1899.00, 29.90,   0.00,  1928.90, 'teslim_edildi', NULL),
('ORD-2024-0049', 44, 44,   399.00, 14.90,   0.00,   413.90, 'teslim_edildi', NULL),
('ORD-2024-0050', 45, 45,  9499.00,  0.00,   0.00,  9499.00, 'kargoda',       '{"customer": "Ayça Yılmaz", "email": "ayca.yilmaz@hotmail.com", "note": "hediye"}'),
('ORD-2024-0051', 46, 46, 42999.00,  0.00,   0.00, 42999.00, 'teslim_edildi', NULL);

-- PAYMENT_RECORDS ek 30 satır (toplam 50)
INSERT INTO payment_records (order_id, amount, currency, pay_method, masked_pan, holder_iban, txn_ref, pay_status, paid_at) VALUES
(31,  9499.00,'TRY','kredi_karti','4716-****-****-0001', NULL,                       'TXN031','başarılı', '2024-02-01 09:10:00'),
(32,   263.90,'TRY','havale',      NULL,                 'TR110006100519786457841333','TXN032','başarılı', '2024-02-01 10:20:00'),
(33,  3328.90,'TRY','kredi_karti','5234-****-****-0002', NULL,                       'TXN033','başarılı', '2024-02-02 11:30:00'),
(34,  8549.00,'TRY','banka_karti','4532-****-****-0003', NULL,                       'TXN034','beklemede','2024-02-02 12:00:00'),
(35, 54999.00,'TRY','kredi_karti','4916-****-****-0004','TR270006100519786457841334','TXN035','başarılı', '2024-02-03 08:45:00'),
(36,   413.90,'TRY','nakit',       NULL,                  NULL,                      'TXN036','başarılı', '2024-02-03 13:15:00'),
(37,  4628.90,'TRY','kredi_karti','5425-****-****-0005', NULL,                       'TXN037','beklemede','2024-02-04 09:00:00'),
(38,  1928.90,'TRY','havale',      NULL,                 'TR430006200372810000006675','TXN038','başarılı', '2024-02-04 15:30:00'),
(39, 42999.00,'TRY','kredi_karti','4485-****-****-0006','TR590006100519786457841335','TXN039','başarılı', '2024-02-05 10:00:00'),
(41,  2999.00,'TRY','kredi_karti','4024-****-****-0007', NULL,                       'TXN041','başarılı', '2024-02-06 11:45:00'),
(42,   713.90,'TRY','banka_karti','4556-****-****-0008', NULL,                       'TXN042','beklemede','2024-02-06 14:20:00'),
(43,  3228.90,'TRY','kredi_karti','4929-****-****-0009','TR750006100519786457841336','TXN043','başarılı', '2024-02-07 09:30:00'),
(44,   263.90,'TRY','havale',      NULL,                 'TR910006200372810000006676','TXN044','beklemede','2024-02-07 16:00:00'),
(45,  8999.00,'TRY','kredi_karti','5267-****-****-0010', NULL,                       'TXN045','başarılı', '2024-02-08 10:15:00'),
(46, 54999.00,'TRY','kredi_karti','4539-****-****-0011','TR130006100519786457841337','TXN046','başarılı', '2024-02-08 17:00:00'),
(47,  4628.90,'TRY','banka_karti','6011-****-****-0012', NULL,                       'TXN047','beklemede','2024-02-09 08:30:00'),
(48,  1928.90,'TRY','havale',      NULL,                 'TR290006200372810000006677','TXN048','başarılı', '2024-02-09 12:45:00'),
(49,   413.90,'TRY','nakit',       NULL,                  NULL,                      'TXN049','başarılı', '2024-02-10 09:00:00'),
(50,  9499.00,'TRY','kredi_karti','4111-****-****-0013','TR450006100519786457841338','TXN050','beklemede','2024-02-10 11:30:00'),
(51, 42999.00,'TRY','kredi_karti','5500-****-****-0014', NULL,                       'TXN051','başarılı', '2024-02-10 16:45:00'),
(6,    413.90,'TRY','kredi_karti','4012-****-****-0015', NULL,                       'TXN006','başarılı', '2024-01-17 10:00:00'),
(20,  1928.90,'TRY','havale',      NULL,                 'TR610006200372810000006678','TXN020','başarılı', '2024-01-22 15:00:00'),
(21,   713.90,'TRY','kredi_karti','4556-****-****-0016', NULL,                       'TXN021','başarılı', '2024-01-23 09:00:00'),
(24,   413.90,'TRY','nakit',       NULL,                  NULL,                      'TXN024','başarılı', '2024-01-23 11:00:00'),
(25,   263.90,'TRY','havale',      NULL,                 'TR770006200372810000006679','TXN025','başarılı', '2024-01-24 08:00:00'),
(27,  3328.90,'TRY','banka_karti','4532-****-****-0017', NULL,                       'TXN027','iade',     '2024-01-24 14:00:00'),
(28,   713.90,'TRY','kredi_karti','4716-****-****-0018','TR830006100519786457841339','TXN028','başarılı', '2024-01-25 10:00:00'),
(29,  2999.00,'TRY','kredi_karti','5234-****-****-0019', NULL,                       'TXN029','beklemede','2024-01-25 12:00:00'),
(8,    233.90,'TRY','kredi_karti','3714-****-****-0020', NULL,                       'TXN008','iade',     '2024-01-17 09:00:00'),
(14,   263.90,'TRY','havale',      NULL,                 'TR990006200372810000006680','TXN014','başarılı', '2024-01-20 10:00:00');

-- PRODUCT_REVIEWS ek 30 satır (toplam 50)
INSERT INTO product_reviews (product_id, cust_id, star_rating, review_title, review_body, is_verified, helpful_cnt) VALUES
(1,  26, 5, 'Mükemmel',            'iPhone 15 Pro gerçekten çok iyi bir telefon.', TRUE,  7),
(2,  27, 4, 'Güzel ama pahalı',    'Samsung kalitesi var ama fiyat yüksek.', FALSE, 3),
(3,  28, 5, 'Süper laptop',        'MacBook M2 ile verimlilik arttı, kesinlikle tavsiye.', TRUE, 18),
(4,  29, 3, 'Fena değil',          'Beklentimi tam karşılamadı ama idare eder.', FALSE, 1),
(5,  30, 5, 'Harika telefon',      'Xiaomi bu fiyata bu kaliteyi nasıl sağlıyor, inanılmaz.', TRUE, 12),
(6,  31, 2, 'Hayal kırıklığı',     'Nike bu fiyata daha iyi olmalıydı.', TRUE, 5),
(7,  32, 5, 'Koşu için ideal',     'Adidas Ultraboost gerçekten fark yaratıyor.', TRUE, 22),
(8,  33, 5, 'Başucu kitabı',       'Her yazılım geliştiricinin okuması gereken bir kitap.', FALSE, 55),
(9,  34, 4, 'İyi sci-fi',          'Dune evrenine hoş bir giriş, devamını da aldım.', TRUE,  9),
(10, 35, 5, 'Alışkanlık değişimi', 'Kitabı okuduktan sonra hayatıma baktım yeniden.', TRUE, 41),
(11, 36, 4, 'Sağlam mat',          'Yoga için çok uygun, kaymıyor ve rahat.', FALSE, 4),
(12, 37, 5, 'Air fryer değer',     'Tefal her zamanki gibi kaliteli. Yağsız pişirme harika.', TRUE, 16),
(13, 38, 4, 'Etkili krem',         'SPF 50 gerçekten işe yarıyor, yanmadım hiç.', TRUE, 8),
(14, 39, 5, 'LEGO aşkı',           'Çocuğum ile birlikte yaptık, harika vakit geçirdik.', TRUE, 27),
(15, 40, 3, 'Orta düzey',          'Silecek işini görüyor ama premium fiyat değil.', FALSE, 2),
(1,  41, 4, 'İyi telefon',         'Fiyatı yüksek ama kalitesi de var, tatmin oldum.', TRUE, 6),
(2,  42, 5, 'Samsung sevdalısı',   'Yıllardır Samsung kullanıyorum, en iyisi bu.', TRUE, 14),
(3,  43, 5, 'Tasarımcının tercihi','Tasarım işleri için MacBook şart, vazgeçilmez.', TRUE, 31),
(5,  44, 4, 'Fiyat/performans',    'Bu fiyata bu özelliklere başka marka vermez.', FALSE, 7),
(6,  45, 5, 'Nike kalitesi',       'Yıllardır Nike giyiyorum, bu model özellikle güzel.', TRUE, 19),
-- Tricky: review_body içinde gömülü iletişim bilgisi
(7,  46, 1, 'İade ettim',          'Ayağımı sıktı, iade. Tolga Arslan 05551112240 arayabilirsiniz.', FALSE, 0),
(8,  47, 5, 'Klasik',              'Yıllar önce okudum, tekrar okuyorum. Her seferinde yeni şeyler keşfediyorum.', TRUE, 38),
(9,  48, 3, 'İdare eder',          'Kitap güzel ama beklentimin biraz altında kaldı.', FALSE, 2),
(10, 49, 5, 'Tavsiye',             'Gizem Aydoğdu - gizem.aydogdu@gmail.com tavsiye eder.', FALSE, 1),
(11, 50, 4, 'Kaliteli',            'Ercan Başak olarak söylüyorum, bu matı alın.', TRUE, 5),
(12, 26, 5, 'Mutfağın vazgeçilmezi','Her gün kullanıyorum artık, çok pratik.', TRUE, 13),
(13, 27, 2, 'Beklenti altında',    'Fiyatına göre daha iyi olmalıydı.', FALSE, 3),
(4,  28, 5, 'AirPods mükemmel',    'Gürültü engelleme özelliği gerçekten işe yarıyor.', TRUE, 24),
(14, 29, 4, 'LEGO kalitesi var',   'Yetişkin olarak yapıyorum, çok eğlenceli.', TRUE, 11),
(15, 30, 5, 'Sağlam ürün',         'Bosch güvenilir marka, kalitesinden emin olun.', TRUE, 8);

-- ACTIVITY_LOGS ek 35 satır (toplam 50)
INSERT INTO activity_logs (session_ref, cust_id, action_type, payload, ip_addr, user_agent) VALUES
('SES016', 26, 'LOGIN',          '{"email": "kerem.yucel@gmail.com", "device": "Android", "success": true}',                                          '10.0.4.11',     'Mozilla/5.0 Android'),
('SES017', 27, 'PURCHASE',       '{"customer": "Büşra Saraç", "email": "busra.sarac@hotmail.com", "order": "ORD-2024-0032", "amount": 263.90}',        '192.168.4.22',  'Mozilla/5.0 Chrome'),
('SES018', 28, 'PROFILE_UPDATE', '{"old_phone": "05551112237", "new_phone": "05551112238", "name": "Ufuk Demirci"}',                                   '10.0.5.33',     'Mozilla/5.0 Firefox'),
('SES019', 29, 'LOGIN',          '{"email": "seyma.kaplan@yahoo.com", "success": true, "device": "iPhone"}',                                          '172.16.3.44',   'Mozilla/5.0 Safari'),
('SES020', 30, 'PURCHASE',       '{"customer": "Okan Güven", "phone": "05441234571", "order": "ORD-2024-0035", "amount": 54999.00}',                   '192.168.5.55',  'Mozilla/5.0 Chrome'),
('SES021', 31, 'ADDRESS_ADD',    '{"name": "Dilara Aslantürk", "phone": "05339876547", "address": "Seyhan Mah. Ziyapaşa Bul. No:45"}',                '10.0.6.66',     'Mozilla/5.0 Firefox'),
('SES022', 32, 'LOGIN',          '{"email": "baris.ozdemir@outlook.com", "tc_no": "22211100990", "success": true}',                                    '192.168.6.77',  'Mozilla/5.0 Chrome'),
('SES023', 33, 'PURCHASE',       '{"customer_name": "Tuğçe Keskin", "email": "tugce.keskin@gmail.com", "total": 1928.90}',                             '10.0.7.88',     'Mozilla/5.0 Safari'),
('SES024', 34, 'RETURN_REQUEST', '{"customer": "Anıl Çakmak", "phone": "05551112238", "order": "ORD-2024-0039"}',                                      '172.16.4.99',   'Mozilla/5.0 Android'),
('SES025', 35, 'LOGIN',          '{"email": "melis.dogru@hotmail.com", "device": "iPad", "success": false}',                                           '192.168.7.100', 'Mozilla/5.0 iPad'),
('SES026', 36, 'PURCHASE',       '{"customer": "Erhan Bayrak", "email": "erhan.bayrak@gmail.com", "iban": "TR770006200372810000006679"}',               '10.0.8.11',     'Mozilla/5.0 Chrome'),
('SES027', 37, 'PROFILE_UPDATE', '{"old_email": "sinem@turan.com", "new_email": "sinem.turan@gmail.com", "phone": "05339876548"}',                     '192.168.8.22',  'Mozilla/5.0 Firefox'),
('SES028', 38, 'LOGIN',          '{"email": "mert.altun@yahoo.com", "success": true}',                                                                 '10.0.9.33',     'Mozilla/5.0 Chrome'),
('SES029', 39, 'PURCHASE',       '{"customer_name": "Elif Bal", "email": "elif.bal@gmail.com", "card_last4": "9012", "amount": 263.90}',                '172.16.5.44',   'Mozilla/5.0 Safari'),
('SES030', 40, 'LOGIN_FAILED',   '{"email": "koray.deniz@icloud.com", "reason": "wrong_password", "attempts": 2}',                                     '192.168.9.55',  'Mozilla/5.0 Chrome'),
('SES031', 41, 'PURCHASE',       '{"customer": "Nazlı Tekin", "email": "nazli.tekin@gmail.com", "amount": 54999.00}',                                  '10.0.10.66',    'Mozilla/5.0 Android'),
('SES032', 42, 'ADDRESS_ADD',    '{"name": "Furkan Akın", "phone": "05441234573", "address": "Etimesgut Mah. İstanbul Yolu No:14"}',                   '192.168.10.77', 'Mozilla/5.0 Firefox'),
('SES033', 43, 'PURCHASE',       '{"customer": "İrem Şimşek", "phone": "05339876549", "email": "irem.simsek@gmail.com", "total": 1928.90}',             '10.0.11.88',    'Mozilla/5.0 Chrome'),
('SES034', 44, 'LOGIN',          '{"email": "cem.ozkan@gmail.com", "tc_no": "88877766654", "device": "Android", "success": true}',                     '172.16.6.99',   'Mozilla/5.0 Android'),
('SES035', 45, 'PURCHASE',       '{"customer_name": "Ayça Yılmaz", "email": "ayca.yilmaz@hotmail.com", "iban": "TR450006100519786457841338"}',          '192.168.11.100','Mozilla/5.0 Chrome'),
('SES036', 46, 'LOGOUT',         '{"email": "tolga.arslan@gmail.com", "session_duration_min": 22}',                                                    '10.0.12.11',    'Mozilla/5.0 Firefox'),
('SES037', 47, 'PROFILE_UPDATE', '{"name": "Derya Kılıç", "old_email": "derya@kilic.com", "new_email": "derya.kilic@yahoo.com"}',                     '192.168.12.22', 'Mozilla/5.0 Safari'),
('SES038', 48, 'PURCHASE',       '{"customer": "Yiğit Cengiz", "email": "yigit.cengiz@gmail.com", "card": "4485-****-****-7777", "amount": 1928.90}',  '10.0.13.33',    'Mozilla/5.0 Chrome'),
('SES039', 49, 'LOGIN',          '{"email": "gizem.aydogdu@gmail.com", "success": true, "device": "iPhone"}',                                          '172.16.7.44',   'Mozilla/5.0 Safari'),
('SES040', 50, 'PURCHASE',       '{"customer_name": "Ercan Başak", "email": "ercan.basak@icloud.com", "total": 42999.00}',                              '192.168.13.55', 'Mozilla/5.0 Chrome'),
('SES041', 1,  'WISHLIST_ADD',   '{"email": "ahmet.yilmaz@gmail.com", "product_id": 3, "product": "MacBook Air M2"}',                                  '10.0.14.66',    'Mozilla/5.0 iPhone'),
('SES042', 5,  'COUPON_APPLY',   '{"email": "mustafa.sahin@outlook.com", "coupon": "SAVE50", "discount": 450.00}',                                     '192.168.14.77', 'Mozilla/5.0 Chrome'),
('SES043', 10, 'PURCHASE',       '{"customer": "Hacer Çetin", "phone": "05321234570", "email": "hacer.cetin@gmail.com", "amount": 699.00}',             '10.0.15.88',    'Mozilla/5.0 Android'),
('SES044', 15, 'PASSWORD_RESET', '{"email": "yusuf.avci@gmail.com", "reset_ip": "10.0.16.99", "success": true}',                                       '10.0.16.99',    'Mozilla/5.0 Firefox'),
('SES045', 20, 'REVIEW_POST',    '{"customer": "Ceren Yavuz", "email": "ceren.yavuz@gmail.com", "product_id": 5, "rating": 1}',                        '192.168.15.100','Mozilla/5.0 Safari'),
('SES046', 25, 'LOGIN',          '{"email": "emre.bozkurt@gmail.com", "tc_no": "77788899001", "success": true}',                                        '10.0.17.11',    'Mozilla/5.0 Chrome'),
('SES047', 30, 'ADDRESS_UPDATE', '{"name": "Okan Güven", "phone": "05441234571", "new_city": "İstanbul", "old_city": "Bursa"}',                        '192.168.16.22', 'Mozilla/5.0 Firefox'),
('SES048', 35, 'PURCHASE',       '{"customer_name": "Melis Doğru", "email": "melis.dogru@hotmail.com", "amount": 219.00}',                             '10.0.18.33',    'Mozilla/5.0 Android'),
('SES049', 40, 'LOGIN',          '{"email": "koray.deniz@icloud.com", "success": true, "device": "MacBook"}',                                          '172.16.8.44',   'Mozilla/5.0 Safari'),
('SES050', 45, 'LOGOUT',         '{"email": "ayca.yilmaz@hotmail.com", "session_duration_min": 67, "pages_visited": 12}',                              '192.168.17.55', 'Mozilla/5.0 Chrome');

-- SUPPORT_TICKETS ek 35 satır (toplam 50)
INSERT INTO support_tickets (cust_id, ticket_ref, subject, body, priority, ticket_status) VALUES
(26, 'TKT-0016', 'Kargo sorunu',      'Kargom 3 gündür gelmedi, takip numarası çalışmıyor.', 'orta',   'açık'),
(27, 'TKT-0017', 'İade talebi',       'Ürünü beğenmedim, iade etmek istiyorum.', 'düşük', 'işlemde'),
-- Tricky: body içinde TC kimlik numarası
(28, 'TKT-0018', 'Fatura gerekli',    'Kurumsal fatura lazım. Ufuk Demirci, TC: 66655544433, VKN: 1234567890', 'orta', 'açık'),
(29, 'TKT-0019', 'Ödeme hatası',      'Ödeme geçti ama sipariş oluşmadı.', 'yüksek', 'işlemde'),
(30, 'TKT-0020', 'Ürün sorusu',       'iPhone 15 Pro kaç yıl garanti?', 'düşük', 'çözüldü'),
(31, 'TKT-0021', 'Adres hatası',      'Yanlış adres girdim, değiştirebilir misiniz?', 'orta', 'açık'),
-- Tricky: body içinde email + tel
(32, 'TKT-0022', 'Hesap erişimi',     'Hesabıma giremiyorum. Barış Özdemir, baris.ozdemir@outlook.com, 05321234578', 'yüksek', 'işlemde'),
(33, 'TKT-0023', 'Kargo gecikmesi',   'Sipariş 1 haftadır kargoda bekliyor.', 'orta', 'açık'),
(34, 'TKT-0024', 'Ürün hasarlı',      'Kutu açıkken geldi, ürün çizik.', 'yüksek', 'işlemde'),
(35, 'TKT-0025', 'İptal talebi',      'Siparişimi iptal etmek istiyorum.', 'düşük', 'çözüldü'),
(36, 'TKT-0026', 'Beden değişimi',    'Yanlış beden aldım, değiştirebilir miyim?', 'orta', 'açık'),
(37, 'TKT-0027', 'Ödeme yöntemi',     'Havale ile ödeme yapabilir miyim?', 'düşük', 'çözüldü'),
-- Tricky: body JSON formatında PII
(38, 'TKT-0028', 'Özel istek',        '{"müşteri": "Mert Altun", "tel": "05321234580", "email": "mert.altun@yahoo.com", "istek": "hediye paketi ve not ekleyin"}', 'orta', 'açık'),
(39, 'TKT-0029', 'Stok sorunu',       'Ürün stokta gözüküyor ama sipariş verilemiyor.', 'orta', 'işlemde'),
(40, 'TKT-0030', 'Kampanya bilgisi',  'Yüzde kaç indirim var bu üründe?', 'düşük', 'çözüldü'),
(41, 'TKT-0031', 'Kargo ücreti',      'Bu ürün için neden kargo ücreti çıktı?', 'düşük', 'çözüldü'),
(42, 'TKT-0032', 'Teslimat süresi',   'Ne zaman gelir bu ürün?', 'düşük', 'çözüldü'),
(43, 'TKT-0033', 'İade para iadesi',  'İadeyi gönderdim, param ne zaman gelir?', 'orta', 'işlemde'),
-- Tricky: body içinde email
(44, 'TKT-0034', 'Fatura isteği',     'E-faturamı cem.ozkan@gmail.com adresine atabilir misiniz?', 'düşük', 'çözüldü'),
(45, 'TKT-0035', 'Ürün yorumu',       'Yorum yazmak istiyorum ama sistem izin vermiyor.', 'düşük', 'açık'),
(46, 'TKT-0036', 'Abonelik iptali',   'E-posta aboneliğini iptal etmek istiyorum.', 'düşük', 'çözüldü'),
(47, 'TKT-0037', 'Kargo firması',     'Hangi kargo firması ile gönderiyorsunuz?', 'düşük', 'çözüldü'),
-- Tricky: body içinde telefon numarası
(48, 'TKT-0038', 'Acil teslimat',     'Çok acil lazım. Yiğit Cengiz, beni arayın: 05441234574', 'yüksek', 'işlemde'),
(49, 'TKT-0039', 'Genel öneri',       'Arama filtrelerini geliştirirseniz çok daha iyi olur.', 'düşük', 'çözüldü'),
(50, 'TKT-0040', 'Promosyon kodu',    'Promosyon kodum neden çalışmıyor?', 'orta', 'açık'),
(1,  'TKT-0041', 'Tekrar sipariş',    'Geçen ay aldığım ürünü tekrar almak istiyorum.', 'düşük', 'çözüldü'),
(5,  'TKT-0042', 'Ürün karşılaştırma','iPhone ile Samsung arasında hangisini önerirsiniz?', 'düşük', 'çözüldü'),
-- Tricky: body içinde IBAN
(10, 'TKT-0043', 'Para iadesi',       'Param IBAN: TR610006200372810000006673 hesabıma gelsin. Hacer Çetin.', 'orta', 'işlemde'),
(15, 'TKT-0044', 'Hesap kapatma',     'Hesabımı kapatmak istiyorum, verilerimi silin.', 'yüksek', 'açık'),
(20, 'TKT-0045', 'Şifre hatası',      'Şifremi sıfırlamak istiyorum ama mail gelmiyor.', 'orta', 'işlemde'),
(25, 'TKT-0046', 'Kapı teslimati',    'Kapıya teslim seçeneği var mı?', 'düşük', 'çözüldü'),
(30, 'TKT-0047', 'Fiyat şikayeti',    'Dün 500 lira ucuzdu, bugün pahalandı. Neden?', 'düşük', 'açık'),
(35, 'TKT-0048', 'Yorum silme',       'Yanlışlıkla yazdığım yorumu silebilir misiniz?', 'düşük', 'çözüldü'),
(40, 'TKT-0049', 'Kargo hasar',       'Kargo hasarlı geldi, fotoğraf attım.', 'yüksek', 'işlemde'),
-- Tricky: body JSON ile PII
(45, 'TKT-0050', 'Özel teslimat',     '{"isim": "Ayça Yılmaz", "email": "ayca.yilmaz@hotmail.com", "telefon": "05449876550", "talep": "saat 18:00dan sonra teslim edilsin"}', 'orta', 'açık');
