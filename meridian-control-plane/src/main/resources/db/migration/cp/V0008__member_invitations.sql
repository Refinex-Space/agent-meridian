-- 按邮箱邀请成员（T19b，ADR-0053）：只给邮箱的成员没有用户标识，用户第一次登录控制台时按令牌中经过验证的邮箱绑定。

ALTER TABLE members ALTER COLUMN subject DROP NOT NULL;
-- 用户标识与邮箱至少有一个：邀请靠邮箱识别
ALTER TABLE members ADD CONSTRAINT members_identity_check CHECK (subject IS NOT NULL OR email <> '');

COMMENT ON COLUMN members.subject IS '主体标识（OIDC 用户标识声明），租户内唯一；按邮箱邀请、尚未登录绑定时为空';
COMMENT ON COLUMN members.email IS '邮箱；空字符串表示没有。用户标识为空（邀请）时用于首次登录绑定，比较不区分大小写';

-- 同一租户中同一邮箱只能有一条未绑定的邀请
CREATE UNIQUE INDEX members_invitation_email_uidx ON members (tenant_id, lower(email)) WHERE subject IS NULL;
-- 首次登录时跨租户按邮箱找未绑定的邀请；绑定后条目移出
CREATE INDEX members_invitation_lookup_idx ON members (lower(email)) WHERE subject IS NULL;
