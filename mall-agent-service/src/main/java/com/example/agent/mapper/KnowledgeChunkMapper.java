package com.example.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.agent.entity.KnowledgeChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /** 批量写入分片，回填自增主键；父片先写、子片后写。 */
    int insertBatch(@Param("list") List<KnowledgeChunk> chunks);

    @Select("SELECT * FROM t_knowledge_chunk WHERE document_id = #{documentId} "
            + "AND chunk_level = #{chunkLevel} AND status = 'ENABLED' ORDER BY chunk_index")
    List<KnowledgeChunk> selectByLevel(@Param("documentId") Long documentId,
                                       @Param("chunkLevel") int chunkLevel);

    @Select("SELECT * FROM t_knowledge_chunk WHERE document_id = #{documentId} ORDER BY id")
    List<KnowledgeChunk> selectByDocumentId(@Param("documentId") Long documentId);

    @Select("SELECT * FROM t_knowledge_chunk WHERE document_id = #{documentId} AND chunk_level = 2 "
            + "AND status = 'ENABLED' AND embedding_status IN ('PENDING', 'FAILED') ORDER BY id")
    List<KnowledgeChunk> selectEmbeddableChildren(@Param("documentId") Long documentId);

    @Select("SELECT * FROM t_knowledge_chunk WHERE document_id = #{documentId} AND chunk_level = 2 "
            + "AND status = 'ENABLED' ORDER BY id")
    List<KnowledgeChunk> selectAllChildren(@Param("documentId") Long documentId);

    @Select("SELECT * FROM t_knowledge_chunk WHERE id = #{id}")
    KnowledgeChunk selectChunkById(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM t_knowledge_chunk WHERE document_id = #{documentId} AND chunk_level = 2 "
            + "AND status = 'ENABLED' AND embedding_status = #{embeddingStatus}")
    int countChildrenByEmbeddingStatus(@Param("documentId") Long documentId,
                                       @Param("embeddingStatus") String embeddingStatus);

    @Update("UPDATE t_knowledge_chunk SET embedding_status = 'READY', embedding_model = #{model}, "
            + "embedding_dim = #{dim}, embedding_input_hash = #{inputHash}, updated_time = #{now} WHERE id = #{id}")
    int markEmbeddingReady(@Param("id") Long id, @Param("model") String model, @Param("dim") int dim,
                           @Param("inputHash") String inputHash, @Param("now") LocalDateTime now);

    @Update("UPDATE t_knowledge_chunk SET embedding_status = #{status}, updated_time = #{now} WHERE id = #{id}")
    int markEmbeddingStatus(@Param("id") Long id, @Param("status") String status,
                            @Param("now") LocalDateTime now);

    /** 物理删除某文档的全部父子分片（文档硬删除时调用，不可恢复）。 */
    @Delete("DELETE FROM t_knowledge_chunk WHERE document_id = #{documentId}")
    int deleteByDocumentId(@Param("documentId") Long documentId);

    @Select("SELECT id FROM t_knowledge_chunk WHERE document_id = #{documentId} AND chunk_level = 2")
    List<Long> selectChildIds(@Param("documentId") Long documentId);

    @Select("<script>SELECT * FROM t_knowledge_chunk WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " ORDER BY id</script>")
    List<KnowledgeChunk> selectByIds(@Param("ids") Collection<Long> ids);
}
