-- ============================================================
-- Test Database: Klinik Yönetim Sistemi
-- PII discovery testi için tasarlanmış karmaşık şema
-- ============================================================

-- Extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ── 1. STAFF (çalışanlar — açık PII) ────────────────────────
CREATE TABLE staff (
    staff_id        SERIAL PRIMARY KEY,
    badge_number    VARCHAR(20) UNIQUE NOT NULL,
    given_name      VARCHAR(100) NOT NULL,       -- PII: first_name
    family_name     VARCHAR(100) NOT NULL,       -- PII: last_name
    contact_email   VARCHAR(255) UNIQUE NOT NULL, -- PII: email
    mobile_contact  VARCHAR(20),                 -- PII: phone
    birth_date      DATE,                        -- PII: date_of_birth
    citizen_no      VARCHAR(11),                 -- PII: tckn
    role            VARCHAR(50),
    department_name VARCHAR(100),
    monthly_salary  NUMERIC(10,2),
    start_date      DATE,
    is_active       BOOLEAN DEFAULT TRUE,
    created_at      TIMESTAMP DEFAULT NOW()
);

-- ── 2. PATIENTS (hastalar — açık PII) ───────────────────────
CREATE TABLE patients (
    patient_id      SERIAL PRIMARY KEY,
    patient_uuid    UUID DEFAULT uuid_generate_v4(),
    name_first      VARCHAR(100) NOT NULL,       -- PII: first_name
    name_last       VARCHAR(100) NOT NULL,       -- PII: last_name
    personal_email  VARCHAR(255),                -- PII: email
    phone_primary   VARCHAR(20),                 -- PII: phone
    phone_secondary VARCHAR(20),                 -- PII: phone
    id_number       VARCHAR(11),                 -- PII: tckn
    date_born       DATE,                        -- PII: date_of_birth
    gender          CHAR(1),
    blood_type      VARCHAR(5),
    emergency_contact_name  VARCHAR(200),        -- PII: full_name
    emergency_contact_phone VARCHAR(20),         -- PII: phone
    insurance_no    VARCHAR(50),                 -- PII: national_id
    registered_at   TIMESTAMP DEFAULT NOW()
);

-- ── 3. APPOINTMENTS (randevular — dolaylı PII) ───────────────
CREATE TABLE appointments (
    appt_id         SERIAL PRIMARY KEY,
    appt_code       VARCHAR(20) UNIQUE NOT NULL,
    patient_id      INTEGER REFERENCES patients(patient_id),
    staff_id        INTEGER REFERENCES staff(staff_id),
    scheduled_at    TIMESTAMP NOT NULL,
    duration_min    INTEGER,
    appt_type       VARCHAR(50),
    room_number     VARCHAR(10),
    status          VARCHAR(20),
    notes           TEXT,                        -- Serbest metin: içinde PII olabilir
    created_at      TIMESTAMP DEFAULT NOW()
);

-- ── 4. MEDICAL_RECORDS (tıbbi kayıtlar — hassas PII) ─────────
CREATE TABLE medical_records (
    record_id       SERIAL PRIMARY KEY,
    patient_id      INTEGER REFERENCES patients(patient_id),
    staff_id        INTEGER REFERENCES staff(staff_id),
    visit_date      DATE NOT NULL,
    diagnosis       TEXT,
    treatment_plan  TEXT,
    vital_signs     JSONB,                       -- JSON içinde PII olabilir
    prescriptions   JSONB,                       -- JSON içinde ilaç bilgisi
    lab_results     JSONB,
    created_at      TIMESTAMP DEFAULT NOW()
);

-- ── 5. INVOICES (faturalar — finansal + PII) ─────────────────
CREATE TABLE invoices (
    invoice_id      SERIAL PRIMARY KEY,
    invoice_number  VARCHAR(30) UNIQUE NOT NULL,
    patient_id      INTEGER REFERENCES patients(patient_id),
    issue_date      DATE NOT NULL,
    due_date        DATE,
    total_amount    NUMERIC(10,2),
    tax_amount      NUMERIC(10,2),
    discount        NUMERIC(10,2),
    payment_status  VARCHAR(20),
    payment_method  VARCHAR(30),
    card_last_four  VARCHAR(4),                  -- Kısmi kart no
    billing_address TEXT,                        -- PII: home_address
    created_at      TIMESTAMP DEFAULT NOW()
);

-- ── 6. STAFF_CREDENTIALS (kimlik bilgileri — kritik PII) ─────
CREATE TABLE staff_credentials (
    cred_id         SERIAL PRIMARY KEY,
    staff_id        INTEGER REFERENCES staff(staff_id),
    national_id     VARCHAR(11),                 -- PII: tckn
    passport_no     VARCHAR(20),                 -- PII: national_id
    drivers_license VARCHAR(20),                 -- PII: national_id
    tax_id          VARCHAR(11),                 -- PII: tax_number
    bank_iban       VARCHAR(34),                 -- PII: iban
    social_sec_no   VARCHAR(20),                 -- PII: SSN
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- ── 7. CLINIC_LOCATIONS (klinik konumları — PII değil) ───────
CREATE TABLE clinic_locations (
    location_id     SERIAL PRIMARY KEY,
    branch_code     VARCHAR(10) UNIQUE NOT NULL,
    branch_name     VARCHAR(100) NOT NULL,
    city            VARCHAR(50),
    district        VARCHAR(50),
    full_address    TEXT,                        -- Bu adres klinik adresi, PII değil
    geo_lat         NUMERIC(10,7),               -- Koordinat (klinik konumu)
    geo_lng         NUMERIC(10,7),               -- Koordinat (klinik konumu)
    phone           VARCHAR(20),                 -- Klinik telefonu, PII değil
    capacity        INTEGER,
    is_open         BOOLEAN DEFAULT TRUE
);

-- ── 8. PATIENT_ADDRESSES (hasta adresleri — PII) ─────────────
CREATE TABLE patient_addresses (
    addr_id         SERIAL PRIMARY KEY,
    patient_id      INTEGER REFERENCES patients(patient_id),
    addr_label      VARCHAR(30),
    street_line     TEXT,                        -- PII: home_address
    neighborhood    VARCHAR(100),
    city            VARCHAR(50),
    postal          VARCHAR(10),
    geo_lat         NUMERIC(10,7),               -- Kişiye bağlı koordinat → PII
    geo_lng         NUMERIC(10,7),               -- Kişiye bağlı koordinat → PII
    is_primary      BOOLEAN DEFAULT FALSE
);

-- ── 9. SYSTEM_LOGS (sistem logları — gizli PII) ──────────────
CREATE TABLE system_logs (
    log_id          SERIAL PRIMARY KEY,
    event_type      VARCHAR(50),
    actor_id        INTEGER,
    target_table    VARCHAR(50),
    target_id       INTEGER,
    before_state    JSONB,                       -- JSON: eski veri, PII içerebilir
    after_state     JSONB,                       -- JSON: yeni veri, PII içerebilir
    client_ip       INET,                        -- PII: ip_address
    user_agent      TEXT,
    occurred_at     TIMESTAMP DEFAULT NOW()
);

-- ── 10. MEDICATIONS (ilaçlar — PII yok) ─────────────────────
CREATE TABLE medications (
    med_id          SERIAL PRIMARY KEY,
    med_code        VARCHAR(20) UNIQUE NOT NULL,
    brand_name      VARCHAR(100),
    generic_name    VARCHAR(100),
    category        VARCHAR(50),
    unit            VARCHAR(20),
    unit_price      NUMERIC(8,2),
    stock_count     INTEGER,
    requires_rx     BOOLEAN DEFAULT TRUE,
    manufacturer    VARCHAR(100),
    expiry_months   INTEGER
);

-- ── 11. FEEDBACK (geri bildirim — serbest metin, gizli PII) ──
CREATE TABLE feedback (
    feedback_id     SERIAL PRIMARY KEY,
    submitted_at    TIMESTAMP DEFAULT NOW(),
    source          VARCHAR(30),
    rating          INTEGER CHECK (rating BETWEEN 1 AND 5),
    category        VARCHAR(50),
    message         TEXT,         -- Kullanıcı "adım X soyadım Y tel: 05xx" yazabilir → PII
    is_resolved     BOOLEAN DEFAULT FALSE,
    resolved_by     INTEGER
);

-- ── 12. INSURANCE_POLICIES (sigorta — kritik PII) ────────────
CREATE TABLE insurance_policies (
    policy_id       SERIAL PRIMARY KEY,
    policy_number   VARCHAR(30) UNIQUE NOT NULL,
    patient_id      INTEGER REFERENCES patients(patient_id),
    provider_name   VARCHAR(100),
    holder_fullname VARCHAR(200),               -- PII: full_name
    holder_id_no    VARCHAR(11),                -- PII: tckn
    holder_dob      DATE,                       -- PII: date_of_birth
    coverage_type   VARCHAR(50),
    start_date      DATE,
    end_date        DATE,
    premium_amount  NUMERIC(10,2),
    is_active       BOOLEAN DEFAULT TRUE
);

-- ═══════════════════════════════════════════════════════════
-- SAMPLE DATA
-- ═══════════════════════════════════════════════════════════

INSERT INTO staff (badge_number, given_name, family_name, contact_email, mobile_contact, birth_date, citizen_no, role, department_name, monthly_salary, start_date) VALUES
('STF001', 'Ayşe',   'Kaya',      'ayse.kaya@klinik.com',    '05321234567', '1985-03-15', '12345678901', 'Doktor',   'Kardiyoloji', 45000, '2018-01-10'),
('STF002', 'Mehmet', 'Demir',     'mehmet.demir@klinik.com', '05339876543', '1979-07-22', '23456789012', 'Doktor',   'Nöroloji',    48000, '2015-06-01'),
('STF003', 'Fatma',  'Çelik',     'fatma.celik@klinik.com',  '05551112233', '1992-11-08', '34567890123', 'Hemşire',  'Genel',       28000, '2020-03-15'),
('STF004', 'Ali',    'Yıldız',    'ali.yildiz@klinik.com',   '05441234567', '1988-05-30', '45678901234', 'Teknisyen','Laboratuvar', 25000, '2019-09-01'),
('STF005', 'Zeynep', 'Arslan',    'zeynep.arslan@klinik.com','05321112233', '1995-01-17', '56789012345', 'Resepsiyon','İdari',      22000, '2021-07-01');

INSERT INTO patients (name_first, name_last, personal_email, phone_primary, phone_secondary, id_number, date_born, gender, blood_type, emergency_contact_name, emergency_contact_phone, insurance_no) VALUES
('Ahmet',   'Yılmaz',  'ahmet.yilmaz@gmail.com',  '05551234567', NULL,          '11122233344', '1975-04-12', 'E', 'A+', 'Hatice Yılmaz',   '05559876543', 'SGK001234'),
('Elif',    'Öztürk',  'elif.ozturk@hotmail.com', '05449876543', '05551231231', '22233344455', '1990-08-25', 'K', 'B+', 'Murat Öztürk',    '05441112222', 'SGK005678'),
('Burak',   'Şahin',   'burak.sahin@gmail.com',   '05321234321', NULL,          '33344455566', '1983-12-01', 'E', 'O-', 'Seda Şahin',      '05329998877', 'OHS009012'),
('Canan',   'Koç',     'canan.koc@yahoo.com',     '05551239999', '05441239999', '44455566677', '2001-03-18', 'K', 'AB+','Veli Koç',        '05553334444', 'SGK003456'),
('İbrahim', 'Çetin',   'ibrahim.cetin@gmail.com', '05329991234', NULL,          '55566677788', '1968-09-07', 'E', 'A-', 'Fatma Çetin',     '05327778888', 'OHS007890');

INSERT INTO appointments (appt_code, patient_id, staff_id, scheduled_at, duration_min, appt_type, room_number, status, notes) VALUES
('APT20240101', 1, 1, '2024-01-15 09:00:00', 30, 'Kontrol', 'K101', 'tamamlandı', 'Hasta rutin kontrol için geldi.'),
('APT20240102', 2, 2, '2024-01-15 10:30:00', 45, 'İlk Muayene', 'N201', 'tamamlandı', 'Baş ağrısı şikayeti. Ahmet Bey ile görüşüldü.'),
('APT20240103', 3, 1, '2024-01-16 14:00:00', 30, 'Takip', 'K101', 'tamamlandı', NULL),
('APT20240104', 4, 3, '2024-01-17 11:00:00', 20, 'Kan Tahlili', 'LAB1', 'tamamlandı', 'Elif hanımın 05449876543 numarasından randevu alındı.'),
('APT20240105', 5, 2, '2024-01-18 16:00:00', 60, 'Konsültasyon', 'N202', 'bekliyor', NULL),
-- Tricky: notes sütununda JSON gömülü PII
('APT20240106', 1, 3, '2024-01-19 09:30:00', 20, 'Takip', 'K102', 'tamamlandı', '{"hasta_adi": "Ahmet Yılmaz", "tc": "11122233344", "telefon": "05551234567", "sikayet": "tansiyon yüksek"}'),
('APT20240107', 2, 4, '2024-01-20 14:00:00', 30, 'Muayene',  'N203', 'tamamlandı', '{"patient_email": "elif.ozturk@hotmail.com", "phone": "05449876543", "note": "migren tekrarladı"}'),
('APT20240108', 4, 1, '2024-01-21 10:00:00', 45, 'Kontrol',  'K101', 'tamamlandı', '{"name": "Canan Koç", "last_name": "Koç", "email": "canan.koc@yahoo.com", "durum": "stabil"}');

INSERT INTO medical_records (patient_id, staff_id, visit_date, diagnosis, treatment_plan, vital_signs, prescriptions, lab_results) VALUES
(1, 1, '2024-01-15', 'Hipertansiyon kontrolü', 'İlaç dozu ayarlandı',
    '{"blood_pressure": "130/85", "pulse": 72, "temperature": 36.6}',
    '[{"drug": "Beloc", "dose": "50mg", "frequency": "1x1"}]',
    -- Tricky: lab_results JSONB içinde gömülü hasta kişisel bilgisi
    '{"patient_name": "Ahmet Yılmaz", "patient_tc": "11122233344", "hba1c": 5.8, "glucose": 95}'),
(2, 2, '2024-01-15', 'Migren', 'Ağrı kesici ve istirahat',
    '{"blood_pressure": "120/80", "pulse": 68, "temperature": 36.4}',
    '[{"drug": "Majezik", "dose": "100mg", "frequency": "2x1"}]',
    '{"patient_name": "Elif Öztürk", "patient_email": "elif.ozturk@hotmail.com", "mri": "normal", "eeg": "anormal"}'),
(3, 1, '2024-01-16', 'Kardiyak takip', 'EKG çekildi normal',
    '{"blood_pressure": "125/82", "pulse": 75, "temperature": 36.7}',
    NULL,
    NULL),
-- Tricky: diagnosis TEXT içinde hasta adı geçiyor
(4, 3, '2024-02-01', 'Canan Koç - Anemi şüphesi, hemoglobin düşük', 'Demir takviyesi başlandı',
    '{"blood_pressure": "110/70", "pulse": 88, "temperature": 36.9}',
    '[{"drug": "Tardyferon", "dose": "80mg", "frequency": "1x1"}]',
    '{"patient_name": "Canan Koç", "patient_phone": "05551239999", "hemoglobin": 9.2, "ferritin": 8}'),
(5, 2, '2024-02-05', 'Diyabet kontrolü', 'Metformin dozajı güncellendi',
    '{"blood_pressure": "135/88", "pulse": 79, "temperature": 36.5}',
    '[{"drug": "Glucophage", "dose": "1000mg", "frequency": "2x1"}]',
    '{"patient_name": "İbrahim Çetin", "patient_email": "ibrahim.cetin@gmail.com", "hba1c": 7.8, "glucose": 165}');

INSERT INTO invoices (invoice_number, patient_id, issue_date, due_date, total_amount, tax_amount, payment_status, payment_method, card_last_four, billing_address) VALUES
('INV-2024-001', 1, '2024-01-15', '2024-02-15', 850.00,  76.50,  'ödendi',   'kredi_karti', '4521', 'Atatürk Mah. Gül Sok. No:5 Kadıköy/İstanbul'),
('INV-2024-002', 2, '2024-01-15', '2024-02-15', 1200.00, 108.00, 'ödendi',   'nakit',       NULL,   'Bağcılar Mah. Lale Cad. No:12 Bağcılar/İstanbul'),
('INV-2024-003', 3, '2024-01-16', '2024-02-16', 650.00,  58.50,  'bekliyor', 'havale',      NULL,   'Çankaya Mah. Ankara Sok. No:3 Çankaya/Ankara');

INSERT INTO staff_credentials (staff_id, national_id, passport_no, tax_id, bank_iban, social_sec_no) VALUES
(1, '12345678901', 'U12345678', '1234567890', 'TR330006100519786457841326', '123-45-6789'),
(2, '23456789012', 'U23456789', '2345678901', 'TR330006100519786457841327', '234-56-7890'),
(3, '34567890123', NULL,        '3456789012', 'TR330006100519786457841328', '345-67-8901');

INSERT INTO clinic_locations (branch_code, branch_name, city, district, full_address, geo_lat, geo_lng, phone, capacity) VALUES
('CLN001', 'Merkez Klinik',    'İstanbul', 'Kadıköy',  'Moda Cad. No:10 Kadıköy',   40.9833, 29.0333, '02161234567', 50),
('CLN002', 'Anadolu Şubesi',   'İstanbul', 'Ümraniye', 'Alemdağ Cad. No:55',         41.0167, 29.1167, '02169876543', 30),
('CLN003', 'Ankara Şubesi',    'Ankara',   'Çankaya',  'Tunalı Hilmi Cad. No:88',    39.9208, 32.8541, '03121234567', 40);

INSERT INTO patient_addresses (patient_id, addr_label, street_line, neighborhood, city, postal, geo_lat, geo_lng, is_primary) VALUES
(1, 'Ev',    'Gül Sokak No:5 D:3',     'Moda',       'İstanbul', '34710', 40.9856, 29.0298, TRUE),
(1, 'İş',    'Bağdat Cad. No:120',     'Suadiye',    'İstanbul', '34740', 40.9612, 29.0752, FALSE),
(2, 'Ev',    'Lale Cad. No:12 D:7',    'Bağcılar',   'İstanbul', '34200', 41.0389, 28.8561, TRUE),
(3, 'Ev',    'Ankara Sok. No:3 D:1',   'Çankaya',    'Ankara',   '06690', 39.9208, 32.8541, TRUE),
(4, 'Ev',    'Cumhuriyet Mah. No:45',  'Konak',      'İzmir',    '35210', 38.4189, 27.1287, TRUE);

INSERT INTO system_logs (event_type, actor_id, target_table, target_id, before_state, after_state, client_ip) VALUES
('UPDATE', 1, 'patients', 1,
    '{"personal_email": "eski@mail.com", "phone_primary": "05551111111"}',
    '{"personal_email": "ahmet.yilmaz@gmail.com", "phone_primary": "05551234567"}',
    '192.168.1.100'),
('INSERT', 3, 'appointments', 5,
    NULL,
    '{"appt_code": "APT20240105", "patient_id": 5, "status": "bekliyor"}',
    '10.0.0.45'),
('DELETE', 2, 'invoices', 99,
    '{"invoice_number": "INV-2023-999", "patient_id": 3, "total_amount": 500}',
    NULL,
    '172.16.0.10'),
-- Tricky: before_state/after_state içinde karmaşık PII
('LOGIN',  4, 'staff', 2,
    NULL,
    '{"username": "dr.mehmet", "email": "mehmet.demir@klinik.com", "ip": "192.168.1.50", "role": "Doktor"}',
    '192.168.1.50'),
('UPDATE', 5, 'patients', 4,
    '{"id_number": "44455566677", "date_born": "2001-03-18", "personal_email": "canan.koc@yahoo.com", "phone_primary": "05551239999"}',
    '{"id_number": "44455566677", "date_born": "2001-03-18", "personal_email": "canan.updated@yahoo.com", "phone_primary": "05551239999"}',
    '10.0.0.80'),
('EXPORT', 1, 'patients', NULL,
    NULL,
    '{"exported_by": "Ayşe Kaya", "email": "ayse.kaya@klinik.com", "record_count": 5, "format": "csv"}',
    '192.168.1.101');

INSERT INTO medications (med_code, brand_name, generic_name, category, unit, unit_price, stock_count, requires_rx, manufacturer) VALUES
('MED001', 'Beloc',    'Metoprolol',    'Kardiyoloji',  'tablet', 45.00,  500, TRUE,  'AstraZeneca'),
('MED002', 'Majezik',  'Flurbiprofen',  'Ağrı Kesici',  'tablet', 28.50,  800, FALSE, 'Abbott'),
('MED003', 'Augmentin','Amoksisilin',   'Antibiyotik',  'tablet', 67.00,  300, TRUE,  'GSK'),
('MED004', 'Parol',    'Parasetamol',   'Ağrı Kesici',  'tablet', 12.00, 2000, FALSE, 'Atabay'),
('MED005', 'Cipro',    'Siprofloksasin','Antibiyotik',  'tablet', 89.00,  150, TRUE,  'Bayer');

INSERT INTO feedback (source, rating, category, message) VALUES
('web',    5, 'hizmet',   'Çok memnun kaldım, doktorlar çok ilgiliydi.'),
('mobil',  3, 'bekleme',  'Bekleme süresi çok uzundu.'),
('web',    4, 'temizlik', 'Adım Canan Koç, telefon numaram 05551239999, randevumda sorun yaşadım lütfen arayın.'),
('telefon',2, 'hizmet',   'İyileşme olmadı, başka kliniğe gidiyorum.'),
('web',    5, 'genel',    'Harika hizmet, teşekkürler!'),
-- Tricky: message sütununda JSON gömülü PII
('mobil',  1, 'şikayet',  '{"isim": "İbrahim Çetin", "email": "ibrahim.cetin@gmail.com", "mesaj": "randevum iptal edildi, bilgi verilmedi"}'),
('web',    2, 'öneri',    '{"name": "Burak Şahin", "phone": "05321234321", "tc_no": "33344455566", "suggestion": "online ödeme sistemi ekleyin"}'),
('api',    4, 'teknik',   '{"first_name": "Elif", "last_name": "Öztürk", "email": "elif.ozturk@hotmail.com", "issue": "uygulama çöktü"}');

INSERT INTO insurance_policies (policy_number, patient_id, provider_name, holder_fullname, holder_id_no, holder_dob, coverage_type, start_date, end_date, premium_amount) VALUES
('POL-SGK-001', 1, 'SGK',      'Ahmet Yılmaz',   '11122233344', '1975-04-12', 'Tam Kapsamlı', '2024-01-01', '2024-12-31', 0.00),
('POL-OHS-001', 2, 'Allianz',  'Elif Öztürk',    '22233344455', '1990-08-25', 'Özel Tam',     '2024-01-01', '2024-12-31', 850.00),
('POL-OHS-002', 3, 'Axa',      'Burak Şahin',    '33344455566', '1983-12-01', 'Temel',        '2024-01-01', '2024-12-31', 450.00),
('POL-SGK-002', 4, 'SGK',      'Canan Koç',      '44455566677', '2001-03-18', 'Tam Kapsamlı', '2024-01-01', '2024-12-31', 0.00),
('POL-OHS-003', 5, 'Generali', 'İbrahim Çetin',  '55566677788', '1968-09-07', 'Özel Tam',     '2024-01-01', '2024-12-31', 920.00);
