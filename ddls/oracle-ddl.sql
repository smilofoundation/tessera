CREATE TABLE ENCRYPTED_TRANSACTION (ENCODED_PAYLOAD BLOB NOT NULL, HASH RAW(100) NOT NULL, PRIMARY KEY (HASH))