CREATE PROCEDURE Vote (phone_number               IN INTEGER,
                       contestant_number          IN INTEGER,
                       max_votes_per_phone_number IN INTEGER)
AS
BEGIN
  SELECT contestant_number FROM contestants WHERE contestant_number = ?;
END;