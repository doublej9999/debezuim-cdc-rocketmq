-- PostgreSQL Replication Slot 清理脚本
-- 用于清理已停止或损坏的 Replication Slot

-- 1. 查看所有 Replication Slot
SELECT slot_name, slot_type, active, restart_lsn FROM pg_replication_slots;

-- 2. 删除特定的 Replication Slot（如果不活跃）
-- 注意：只能删除不活跃的 Slot
SELECT pg_drop_replication_slot('debezium_slot_1');
SELECT pg_drop_replication_slot('debezium_slot_2');
SELECT pg_drop_replication_slot('debezium_slot_3');

-- 3. 如果 Slot 仍然活跃，先终止相关的连接
-- 查找占用 Slot 的进程
SELECT pid, usename, application_name, state FROM pg_stat_activity
WHERE application_name LIKE '%debezium%';

-- 4. 终止特定进程（如果需要）
-- SELECT pg_terminate_backend(pid) FROM pg_stat_activity
-- WHERE application_name LIKE '%debezium%';

-- 5. 删除相关的 Publication
DROP PUBLICATION IF EXISTS debezium_publication_1;
DROP PUBLICATION IF EXISTS debezium_publication_2;
DROP PUBLICATION IF EXISTS debezium_publication_3;

-- 6. 清理所有 Debezium 相关的 Slot 和 Publication
-- 删除所有以 debezium_slot 开头的 Slot
DO $$
DECLARE
    slot_record RECORD;
BEGIN
    FOR slot_record IN
        SELECT slot_name FROM pg_replication_slots
        WHERE slot_name LIKE 'debezium_slot_%' AND NOT active
    LOOP
        EXECUTE 'SELECT pg_drop_replication_slot(''' || slot_record.slot_name || ''')';
        RAISE NOTICE 'Dropped slot: %', slot_record.slot_name;
    END LOOP;
END $$;

-- 7. 删除所有以 debezium_publication 开头的 Publication
DO $$
DECLARE
    pub_record RECORD;
BEGIN
    FOR pub_record IN
        SELECT pubname FROM pg_publication
        WHERE pubname LIKE 'debezium_publication_%'
    LOOP
        EXECUTE 'DROP PUBLICATION IF EXISTS ' || pub_record.pubname;
        RAISE NOTICE 'Dropped publication: %', pub_record.pubname;
    END LOOP;
END $$;

-- 8. 验证清理结果
SELECT slot_name, slot_type, active FROM pg_replication_slots;
SELECT pubname FROM pg_publication WHERE pubname LIKE 'debezium_publication_%';


select *
from pg_publication;
