-- Run as the restricted alfred role. No extensions or administrator access needed.
BEGIN;
CREATE TABLE IF NOT EXISTS alfred.requests (
    user_id text NOT NULL,
    request_id text NOT NULL,
    payload jsonb NOT NULL,
    owner_id text NOT NULL,
    state text NOT NULL DEFAULT 'received'
        CHECK (state IN ('received', 'awaiting_clarification', 'ready', 'executing', 'completed', 'failed', 'unknown')),
    response jsonb,
    proposal jsonb,
    provider_id text,
    provider_receipt jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, request_id)
);

CREATE TABLE IF NOT EXISTS alfred.clarifications (
    token uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id text NOT NULL,
    request_id text NOT NULL,
    conversation_id text NOT NULL,
    proposal jsonb NOT NULL,
    expires_at timestamptz NOT NULL DEFAULT now() + interval '24 hours',
    consumed_by text,
    FOREIGN KEY (user_id, request_id) REFERENCES alfred.requests (user_id, request_id),
    FOREIGN KEY (user_id, consumed_by) REFERENCES alfred.requests (user_id, request_id)
);

-- The conflict update locks the existing row and returns it even when a
-- concurrent insertion was not visible to the statement's initial snapshot.
-- An execution owns only rows bearing its unique n8n execution ID.
CREATE OR REPLACE FUNCTION alfred.claim_request(p_user text, p_request text, p_payload jsonb, p_owner text)
RETURNS TABLE (disposition text, record jsonb)
LANGUAGE plpgsql AS $$
DECLARE r alfred.requests;
BEGIN
    INSERT INTO alfred.requests AS existing (user_id, request_id, payload, owner_id)
    VALUES (p_user, p_request, p_payload, p_owner)
    ON CONFLICT (user_id, request_id) DO UPDATE SET request_id = existing.request_id
    RETURNING * INTO r;
    disposition := CASE
        WHEN r.payload <> p_payload THEN 'conflict'
        WHEN r.response IS NOT NULL THEN 'replay'
        WHEN r.owner_id = p_owner AND r.state = 'received' THEN 'claimed'
        ELSE 'pending'
    END;
    record := to_jsonb(r);
    RETURN NEXT;
END;
$$;
COMMIT;
