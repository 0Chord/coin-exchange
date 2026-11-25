-- 한 행이 전체 마켓 목록의 고정 버전이다. 다른 내용은 새 버전으로 등록한다.
create table market_configuration_release (
    config_version bigint primary key check (config_version > 0),
    markets_document text not null check (octet_length(markets_document) between 1 and 65536),
    created_at timestamptz not null default now()
);

create function reject_market_configuration_release_change() returns trigger
language plpgsql as $$
begin
    raise exception 'market configuration releases cannot be changed or removed';
end;
$$;

create trigger market_configuration_release_immutable
    before update or delete or truncate on market_configuration_release
    for each statement execute function reject_market_configuration_release_change();
