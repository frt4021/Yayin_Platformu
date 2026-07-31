
-- Kullanıcı ve rol tabloları.
--
-- Kimlik doğrulama ve şifreler Keycloak'ta kalır; bu tablolar uygulamanın
-- kendi verisini (yayın, kanal, kayıt vb.) bir kullanıcıya bağlayabilmesi
-- için tutulan yerel eşleştirmedir. Bağ noktası users.keycloak_id'dir.

create table roles
(
    id   uuid        primary key default gen_random_uuid(),
    name varchar(32) not null unique
);

comment on table roles is 'Uygulama rolleri. İsimler Keycloak realm rolleriyle birebir aynıdır.';

insert into roles (name)
values ('ADMIN'),
       ('MODERATOR'),
       ('VIEWER');

create table users
(
    id          uuid         primary key default gen_random_uuid(),
    keycloak_id varchar(36)  not null unique,
    username    varchar(64)  not null unique,
    role_id     uuid         not null references roles (id),
    created_at  timestamptz  not null default now()
);

comment on column users.keycloak_id is 'Keycloak kullanıcısının sub/id değeri — token''daki subject ile eşleşir.';

-- Rol bazlı listeleme (ör. "tüm moderatörler") için; foreign key tek başına index oluşturmaz.
create index idx_users_role_id on users (role_id);
