BEGIN;
CREATE TABLE IF NOT EXISTS alfred.workouts (
    user_id text NOT NULL,
    workout_id uuid NOT NULL DEFAULT gen_random_uuid(),
    revision integer NOT NULL CHECK (revision > 0),
    data jsonb NOT NULL CHECK (jsonb_typeof(data) = 'object'),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, workout_id)
);
CREATE TABLE IF NOT EXISTS alfred.workout_revisions (
    user_id text NOT NULL,
    workout_id uuid NOT NULL,
    revision integer NOT NULL,
    request_id text NOT NULL,
    data jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, workout_id, revision),
    UNIQUE (user_id, request_id),
    FOREIGN KEY (user_id, workout_id) REFERENCES alfred.workouts(user_id, workout_id),
    FOREIGN KEY (user_id, request_id) REFERENCES alfred.requests(user_id, request_id)
);

-- Ledger completion and the revision commit together; a lost HTTP response is
-- replayed from requests rather than writing the workout a second time.
CREATE OR REPLACE FUNCTION alfred.save_workout(
    p_user text, p_request text, p_owner text, p_data jsonb,
    p_target uuid, p_revision integer
) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE r alfred.requests; w alfred.workouts; result jsonb;
BEGIN
    SELECT * INTO r FROM alfred.requests
    WHERE user_id=p_user AND request_id=p_request FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Request not claimed'; END IF;
    IF r.response IS NOT NULL THEN RETURN r.response; END IF;
    IF r.owner_id<>p_owner OR r.state<>'received' THEN
        RAISE EXCEPTION 'Request not owned';
    END IF;
    IF p_target IS NULL THEN
        INSERT INTO alfred.workouts(user_id,revision,data)
        VALUES(p_user,1,p_data) RETURNING * INTO w;
    ELSE
        UPDATE alfred.workouts SET revision=revision+1,data=p_data,updated_at=now()
        WHERE user_id=p_user AND workout_id=p_target AND revision=p_revision
        RETURNING * INTO w;
        IF NOT FOUND THEN
            result:=jsonb_build_object('status','failed','responseText',
                'This workout changed since the selected receipt. Reply to the latest workout receipt to correct it.');
            UPDATE alfred.requests SET state='failed',response=result,updated_at=now()
            WHERE user_id=p_user AND request_id=p_request;
            RETURN result;
        END IF;
    END IF;
    INSERT INTO alfred.workout_revisions(user_id,workout_id,revision,request_id,data)
    VALUES(p_user,w.workout_id,w.revision,p_request,p_data);
    result:=jsonb_build_object('status','completed',
        'responseText',CASE WHEN p_target IS NULL THEN 'Workout logged: ' ELSE 'Workout corrected: ' END
            || (p_data->>'activity') || ' at ' || (p_data->>'performedAt')
            || ' (' || (p_data->>'timeZone') || '). Revision ' || w.revision || '.',
        'conversationId',coalesce(r.payload->>'conversationId',r.payload->>'sessionId'),
        'receipt',jsonb_build_object('action',CASE WHEN p_target IS NULL THEN 'Workout logged' ELSE 'Workout corrected' END,
            'externalId',w.workout_id::text));
    UPDATE alfred.requests SET state='completed',response=result,
        provider_receipt=jsonb_build_object('kind','workout','id',w.workout_id,
            'revision',w.revision,'data',p_data),updated_at=now()
    WHERE user_id=p_user AND request_id=p_request;
    RETURN result;
END;
$$;
COMMIT;
