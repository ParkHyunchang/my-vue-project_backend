# History / Dating content limits

Both forms use `src/components/timeline/TimelineFormModal.vue` in the frontend.
Both entities inherit `TimelineContent` in the backend.

- Description: MySQL TEXT, at most 65,535 UTF-8 bytes. ASCII: 65,535 characters; Hangul syllables: 21,845; four-byte emoji: 16,383. Mixed text varies. Both form and create/update services reject overflow.
- Media per record: frontend allows 20 images and videos combined (including existing attachments).
- Development/production frontend settings: 100 MiB per image, 3 GiB per video. Fallback when variables are absent: 20 MiB / 200 MiB.
- Backend multipart limit: local/prod 3 GiB per file; default/docker/nas 1 GiB per file. Thus Docker/NAS videos are effectively limited to 1 GiB despite the frontend 3 GiB setting. Actual deployed environment overrides must also be checked.
- Each file is a separate request. Nginx is configured for 5 GiB per request. Media count/type-specific size checks are frontend checks, not equivalent server quotas.
- HEIC/HEIF images are converted to JPEG by the shared form.

## Database rollout

Apply `alter_history_description_text.sql` to the target MySQL database before deploying with `ddl-auto=validate`. With `ddl-auto=update`, verify the resulting history.description column is TEXT after startup. The SQL preserves existing descriptions. This change does not execute SQL against a deployed database.

Verification query:

```sql
SHOW COLUMNS FROM history LIKE 'description';
SHOW COLUMNS FROM dating LIKE 'description';
```
