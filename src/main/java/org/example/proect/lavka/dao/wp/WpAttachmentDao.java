package org.example.proect.lavka.dao.wp;

import org.example.proect.lavka.utils.RetryLabel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@RetryLabel("WpAttachmentDao")
@Repository
public class WpAttachmentDao {
    private final JdbcTemplate jdbc;
    public WpAttachmentDao(@Qualifier("wpJdbcTemplate") JdbcTemplate jdbc){ this.jdbc = jdbc; }

    /** upsert _wp_attachment_image_alt */
    public void upsertAlt(long attId, String alt){
        int upd = jdbc.update("""
            UPDATE wp_postmeta SET meta_value=? WHERE post_id=? AND meta_key='_wp_attachment_image_alt'
        """, alt, attId);
        if (upd == 0) {
            jdbc.update("""
                INSERT INTO wp_postmeta(post_id, meta_key, meta_value)
                VALUES(?, '_wp_attachment_image_alt', ?)
            """, attId, alt);
        }
    }

    /** обновить post_title и post_name (slug) у attachment */
    public void updateTitleAndSlug(long attId, String title, String slug){
        jdbc.update("""
            UPDATE wp_posts
            SET post_title=?, post_name=?
            WHERE ID=? AND post_type='attachment'
        """, title, slug, attId);
    }

    /** при необходимости — синхронизировать относительный путь */
    public void ensureAttachedFile(long attId, String s3Key){
        int upd = jdbc.update("""
            UPDATE wp_postmeta SET meta_value=? WHERE post_id=? AND meta_key='_wp_attached_file'
        """, s3Key, attId);
        if (upd == 0) {
            jdbc.update("""
                INSERT INTO wp_postmeta(post_id, meta_key, meta_value)
                VALUES(?, '_wp_attached_file', ?)
            """, attId, s3Key);
        }
    }
}
