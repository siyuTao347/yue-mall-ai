package com.example.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.agent.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

    @Select("SELECT MAX(version) FROM t_knowledge_document WHERE doc_no = #{docNo}")
    Integer selectMaxVersion(@Param("docNo") String docNo);

    @Select("SELECT * FROM t_knowledge_document WHERE doc_no = #{docNo} AND version = #{version}")
    KnowledgeDocument selectByDocNoAndVersion(@Param("docNo") String docNo, @Param("version") Integer version);

    @Select("SELECT * FROM t_knowledge_document WHERE doc_no = #{docNo} ORDER BY version DESC LIMIT 1")
    KnowledgeDocument selectLatestByDocNo(@Param("docNo") String docNo);

    @Update("UPDATE t_knowledge_document SET status = #{status}, updated_by = #{operatorId}, updated_time = #{now} "
            + "WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status,
                     @Param("operatorId") Long operatorId, @Param("now") LocalDateTime now);

    @Update("UPDATE t_knowledge_document SET status = 'DISABLED', updated_by = #{operatorId}, updated_time = #{now} "
            + "WHERE doc_no = #{docNo} AND version <> #{keepVersion}")
    int disableOtherVersions(@Param("docNo") String docNo, @Param("keepVersion") Integer keepVersion,
                             @Param("operatorId") Long operatorId, @Param("now") LocalDateTime now);

    @Update("UPDATE t_knowledge_document SET index_status = #{indexStatus}, parent_chunk_count = #{parentCount}, "
            + "child_chunk_count = #{childCount}, char_count = #{charCount}, updated_by = #{operatorId}, "
            + "updated_time = #{now} WHERE id = #{id}")
    int updateSplitStat(@Param("id") Long id, @Param("indexStatus") String indexStatus,
                        @Param("parentCount") int parentCount, @Param("childCount") int childCount,
                        @Param("charCount") int charCount, @Param("operatorId") Long operatorId,
                        @Param("now") LocalDateTime now);

    @Update("UPDATE t_knowledge_document SET index_status = #{indexStatus}, updated_time = #{now} WHERE id = #{id}")
    int updateIndexStatus(@Param("id") Long id, @Param("indexStatus") String indexStatus,
                          @Param("now") LocalDateTime now);
}
