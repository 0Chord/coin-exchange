alter table ledger_transactions drop constraint ck_ledger_transactions_type;
alter table ledger_transactions add constraint ck_ledger_transactions_type
    check (transaction_type in ('OPENING', 'RESERVE', 'RELEASE', 'SETTLEMENT', 'REVERSAL'));
