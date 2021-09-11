-- -------------------------------------------------------------------------------------------------
-- Database Name: mosip_idrepo
-- Table Name 	: idrepo.channel_info
-- Purpose    	: channel_info: Channel information for reporting purpose.
--           
-- Create By   	: Manoj SP
-- Created Date	: 10-Sep-2021
-- 
-- Modified Date        Modified By         Comments / Remarks
-- ------------------------------------------------------------------------------------------
-- Sep-2021             Manoj SP            Created channel_info table 
-- ------------------------------------------------------------------------------------------

-- object: idrepo.channel_info | type: TABLE --
-- DROP TABLE IF EXISTS idrepo.channel_info CASCADE;
         
-- ddl-end --
COMMENT ON TABLE idrepo.channel_info IS 'channel_info: Anonymous profiling information for reporting purpose.';
-- ddl-end --
COMMENT ON COLUMN idrepo.channel_info.id IS 'Reference ID: System generated id for references in the system.';
-- ddl-end --
COMMENT ON COLUMN idrepo.channel_info.profile IS 'Profile : Contains complete anonymous profile data generated by ID-Repository and stored in plain json text format.';
-- ddl-end --
COMMENT ON COLUMN idrepo.channel_info.cr_by IS 'Created By : ID or name of the user who create / insert record';
-- ddl-end --
COMMENT ON COLUMN idrepo.channel_info.cr_dtimes IS 'Created DateTimestamp : Date and Timestamp when the record is created/inserted';
-- ddl-end --
COMMENT ON COLUMN idrepo.channel_info.upd_by IS 'Updated By : ID or name of the user who update the record with new values';
-- ddl-end --
COMMENT ON COLUMN idrepo.channel_info.upd_dtimes IS 'Updated DateTimestamp : Date and Timestamp when any of the fields in the record is updated with new values.';
-- ddl-end --
COMMENT ON COLUMN idrepo.channel_info.is_deleted IS 'IS_Deleted : Flag to mark whether the record is Soft deleted.';
-- ddl-end --
COMMENT ON COLUMN idrepo.channel_info.del_dtimes IS 'Deleted DateTimestamp : Date and Timestamp when the record is soft deleted with is_deleted=TRUE';
-- ddl-end --
