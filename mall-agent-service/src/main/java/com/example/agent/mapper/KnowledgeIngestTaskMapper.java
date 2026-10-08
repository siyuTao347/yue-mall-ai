package com.example.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.agent.entity.KnowledgeIngestTask;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface KnowledgeIngestTaskMapper extends BaseMapper<KnowledgeIngestTask> {

    @Select("SELECT * FROM t_knowledge_ingest_task WHERE document_id = #{documentId} "
            + "AND status IN ('PENDING', 'RUNNING') ORDER BY id DESC LIMIT 1")
    KnowledgeIngestTask selectActiveByDocumentId(@Param("documentId") Long documentId);

    /** 取消进行中的任务：删除文档时调用，工作线程按批检测后停止写入向量。 */
    @Update("UPDATE t_knowledge_ingest_task SET status = 'CANCELED', last_error = #{reason}, finished_time = #{now} "
            + "WHERE document_id = #{documentId} AND status IN ('PENDING', 'RUNNING')")
    int cancelActiveByDocumentId(@Param("documentId") Long documentId, @Param("reason") String reason,
                                 @Param("now") LocalDateTime now);

    @Select("SELECT * FROM t_knowledge_ingest_task WHERE task_no = #{taskNo}")
    KnowledgeIngestTask selectByTaskNo(@Param("taskNo") String taskNo);

    /** 物理删除某文档的全部入库任务记录（文档硬删除时调用，避免留下悬空行）。 */
    @Delete("DELETE FROM t_knowledge_ingest_task WHERE document_id = #{documentId}")
    int deleteByDocumentId(@Param("documentId") Long documentId);
}
