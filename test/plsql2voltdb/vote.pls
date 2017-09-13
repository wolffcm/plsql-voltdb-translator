CREATE PROCEDURE Vote (phone_number_in               IN  INTEGER,
                       contestant_number_in          IN  INTEGER,
                       max_votes_per_phone_number_in IN  INTEGER,
                       return_code_out               OUT INTEGER)
AS
DECLARE
    -- Error codes
    VOTE_SUCCESSFUL            CONSTANT INTEGER := 0;
    ERR_INVALID_CONTESTANT     CONSTANT INTEGER := 1;
    ERR_VOTER_OVER_VOTE_LIMIT  CONSTANT INTEGER := 2;

    contestant_count   INTEGER;
    num_existing_votes INTEGER;
    state_abbrev       votes.state%TYPE DEFAULT 'XX';
BEGIN
    -- Validate that this is a valid contestant
    SELECT COUNT(*)
        INTO contestant_count
        FROM contestants
        WHERE contestant_number = contestant_number_in;
    IF contestant_count = 0 THEN
        return_code_out := ERR_INVALID_CONTESTANT;
        RETURN;
    END IF;

    -- Check to see if this voter is over the vote limit
	SELECT SUM(num_votes)
	    INTO num_existing_votes
	    FROM v_votes_by_phone_number
	    WHERE phone_number = phone_number_in;
    IF num_existing_votes >= max_votes_per_phone_number_in THEN
        return_code_out := ERR_VOTER_OVER_VOTE_LIMIT;
        RETURN;
    END IF;

    -- Find the state of the voter based on area code
    FOR state_row IN
        ( SELECT state FROM area_code_state
          WHERE area_code = FLOOR(phone_number_in / 10000000) )
    LOOP
        state_abbrev := state_row.state;
    END LOOP;

    -- Finally, insert the vote
    INSERT INTO votes VALUES (phone_number_in, state_abbrev, contestant_number_in);

    return_code_out := VOTE_SUCCESSFUL;
END;
