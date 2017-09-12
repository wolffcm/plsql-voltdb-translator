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

  return_code_out := VOTE_SUCCESSFUL;
END;