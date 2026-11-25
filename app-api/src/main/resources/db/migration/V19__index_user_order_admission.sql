create index order_query_user_admission_idx
    on order_query.order_record (namespace, (record_payload ->> 'userId'), admission_sequence desc);
