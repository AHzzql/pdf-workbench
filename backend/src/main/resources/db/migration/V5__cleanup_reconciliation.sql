CREATE INDEX work_tasks_failed_cleanup ON work_tasks(updated_at,id)
    WHERE kind='CLEAN' AND state='FAILED';
