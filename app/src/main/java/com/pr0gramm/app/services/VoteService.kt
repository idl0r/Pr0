package com.pr0gramm.app.services

import com.pr0gramm.app.api.pr0gramm.Api
import com.pr0gramm.app.decodeBase64
import com.pr0gramm.app.feed.FeedItem
import com.pr0gramm.app.orm.CachedVote
import com.pr0gramm.app.orm.CachedVote.Type.ITEM
import com.pr0gramm.app.orm.Vote
import com.pr0gramm.app.util.AndroidUtility.checkNotMainThread
import com.pr0gramm.app.util.Databases.withTransaction
import com.pr0gramm.app.util.Stopwatch
import com.pr0gramm.app.util.doInBackground
import com.pr0gramm.app.util.subscribeOnBackground
import com.squareup.sqlbrite.BriteDatabase
import gnu.trove.TCollections
import gnu.trove.map.TLongObjectMap
import gnu.trove.map.hash.TLongObjectHashMap
import org.slf4j.LoggerFactory
import rx.Completable
import rx.Observable
import java.io.*


/**
 */

class VoteService(private val api: Api,
                  private val seenService: SeenService,
                  private val database: BriteDatabase) {

    /**
     * Votes a post. This sends a request to the server, so you need to be signed in
     * to vote posts.

     * @param item The item that is to be voted
     * @param vote The vote to send to the server
     */
    fun vote(item: FeedItem, vote: Vote): Completable {
        logger.info("Voting feed item {} {}", item.id, vote)
        Track.votePost(vote)

        doInBackground { storeVoteValueInTx(CachedVote.Type.ITEM, item.id, vote) }
        return api.vote(null, item.id, vote.voteValue).toCompletable()
    }

    fun vote(comment: Api.Comment, vote: Vote): Completable {
        logger.info("Voting comment {} {}", comment.id, vote)
        Track.voteComment(vote)

        doInBackground { storeVoteValueInTx(CachedVote.Type.COMMENT, comment.id, vote) }
        return api.voteComment(null, comment.id, vote.voteValue).toCompletable()
    }

    fun vote(tag: Api.Tag, vote: Vote): Completable {
        logger.info("Voting tag {} {}", tag.id, vote)
        Track.voteTag(vote)

        doInBackground { storeVoteValueInTx(CachedVote.Type.TAG, tag.id, vote) }
        return api.voteTag(null, tag.id, vote.voteValue).toCompletable()
    }

    /**
     * Observes the votes for an item.
     * @param item The item to get the vote for.
     */
    fun getVote(item: FeedItem): Observable<Vote> {
        return CachedVote.find(database, ITEM, item.id)
                .map<Vote> { vote -> vote.vote }
                .subscribeOnBackground()
    }

    /**
     * Stores the vote value. This creates a transaction to prevent lost updates.

     * @param type   The type of vote to store in the vote cache.
     * @param itemId The id of the item to vote
     * @param vote   The vote to store for that item
     */
    private fun storeVoteValueInTx(type: CachedVote.Type, itemId: Long, vote: Vote) {
        checkNotMainThread()
        withTransaction(database) {
            storeVoteValue(type, itemId, vote)
        }
    }

    /**
     * Stores a vote value for an item with the given id.
     * This method must be called inside of an transaction to guarantee
     * consistency.

     * @param type   The type of vote to store in the vote cache.
     * *
     * @param itemId The id of the item to vote
     * *
     * @param vote   The vote to store for that item
     */
    private fun storeVoteValue(type: CachedVote.Type, itemId: Long, vote: Vote) {
        checkNotMainThread()
        CachedVote.quickSave(database, type, itemId, vote)
    }

    /**
     * Applies the given voting actions from the log.

     * @param actions The actions from the log to apply.
     */
    fun applyVoteActions(actions: String) {
        if (actions.isEmpty())
            return

        val decoded = actions.decodeBase64()
        require(decoded.size % 5 == 0) {
            "Length of vote log must be a multiple of 5"
        }



        val actionCount = decoded.size / 5
        val actionStream = LittleEndianDataInputStream(ByteArrayInputStream(decoded))

        val watch = Stopwatch.createStarted()
        withTransaction(database) {
            logger.info("Applying {} vote actions", actionCount)
            for (idx in 0 until actionCount) {
                val id = actionStream.readInt().toLong()
                val action = VOTE_ACTIONS[actionStream.readUnsignedByte()] ?: continue

                storeVoteValue(action.type, id, action.vote)
                if (action.type == ITEM) {
                    seenService.markAsSeen(id)
                }
            }
        }

        logger.info("Applying vote actions took {}", watch.toString())
    }

    /**
     * Tags the given post. This methods adds the tags to the given post
     * and returns a list of tags.
     */
    fun tag(itemId: Long, tags: List<String>): Observable<List<Api.Tag>> {
        val tagString = tags.map { tag -> tag.replace(',', ' ') }.joinToString(",")
        return api.addTags(null, itemId, tagString).map { response ->
            withTransaction(database) {
                // auto-apply up-vote to newly created tags
                for (tagId in response.tagIds) {
                    storeVoteValue(CachedVote.Type.TAG, tagId, Vote.UP)
                }
            }

            response.tags
        }
    }

    /**
     * Writes a comment to the given post.
     */
    fun postComment(itemId: Long, parentId: Long, comment: String): Observable<Api.NewComment> {
        return api.postComment(null, itemId, parentId, comment)
                .filter { response -> response.comments.size >= 1 }
                .doOnNext { response ->
                    // store the implicit upvote for the comment.
                    storeVoteValueInTx(CachedVote.Type.COMMENT, response.commentId, Vote.UP)
                }
    }

    fun postComment(item: FeedItem, parentId: Long, comment: String): Observable<Api.NewComment> {
        return postComment(item.id, parentId, comment)
    }

    /**
     * Removes all votes from the vote cache.
     */
    fun clear() {
        logger.info("Removing all items from vote cache")
        CachedVote.clear(database)
    }

    /**
     * Gets the votes for the given comments

     * @param comments A list of comments to get the votes for.
     * *
     * @return A map containing the vote from commentId to vote
     */
    fun getCommentVotes(comments: List<Api.Comment>): Observable<TLongObjectMap<Vote>> {
        val ids = comments.map { it.id }
        return findCachedVotes(CachedVote.Type.COMMENT, ids)
    }

    fun getTagVotes(tags: List<Api.Tag>): Observable<TLongObjectMap<Vote>> {
        val ids = tags.map { it.id }
        return findCachedVotes(CachedVote.Type.TAG, ids)
    }

    val summary: Observable<Map<CachedVote.Type, Summary>> = Observable.fromCallable {
        val counts = CachedVote.count(database.readableDatabase)

        counts.mapValues { entry ->
            Summary(up = entry.value[Vote.UP] ?: 0,
                    down = entry.value[Vote.DOWN] ?: 0,
                    fav = entry.value[Vote.FAVORITE] ?: 0)
        }
    }

    private fun findCachedVotes(type: CachedVote.Type, ids: List<Long>): Observable<TLongObjectMap<Vote>> {
        return CachedVote.find(database, type, ids).map { cachedVotes ->
            val result = TLongObjectHashMap<Vote>(cachedVotes.size)
            for (cachedVote in cachedVotes)
                result.put(cachedVote.itemId, cachedVote.vote)

            // add "NEUTRAL" votes for every unknown item
            ids.forEach { result.putIfAbsent(it, Vote.NEUTRAL) }

            result
        }
    }

    private class VoteAction internal constructor(internal val type: CachedVote.Type, internal val vote: Vote)

    data class Summary(val up: Int, val down: Int, val fav: Int)

    companion object {
        val NO_VOTES: TLongObjectMap<Vote> = TCollections.unmodifiableMap(TLongObjectHashMap<Vote>())

        private val logger = LoggerFactory.getLogger("VoteService")

        private val VOTE_ACTIONS = mapOf(
                1 to VoteAction(CachedVote.Type.ITEM, Vote.DOWN),
                2 to VoteAction(CachedVote.Type.ITEM, Vote.NEUTRAL),
                3 to VoteAction(CachedVote.Type.ITEM, Vote.UP),
                4 to VoteAction(CachedVote.Type.COMMENT, Vote.DOWN),
                5 to VoteAction(CachedVote.Type.COMMENT, Vote.NEUTRAL),
                6 to VoteAction(CachedVote.Type.COMMENT, Vote.UP),
                7 to VoteAction(CachedVote.Type.TAG, Vote.DOWN),
                8 to VoteAction(CachedVote.Type.TAG, Vote.NEUTRAL),
                9 to VoteAction(CachedVote.Type.TAG, Vote.UP),
                10 to VoteAction(CachedVote.Type.ITEM, Vote.FAVORITE),
                11 to VoteAction(CachedVote.Type.COMMENT, Vote.FAVORITE))
    }


    private class LittleEndianDataInputStream(private val input: InputStream) : FilterInputStream(input) {
        fun readUnsignedByte(): Int {
            val b1 = input.read()
            if (0 > b1) {
                throw EOFException()
            }

            return b1
        }

        /**
         * Reads an integer as specified by [DataInputStream.readInt], except using little-endian
         * byte order.
         *
         * @return the next four bytes of the input stream, interpreted as an `int` in little-endian
         * byte order
         * @throws IOException if an I/O error occurs
         */
        fun readInt(): Int {
            val b1 = readAndCheckByte().toInt()
            val b2 = readAndCheckByte().toInt() shl 8
            val b3 = readAndCheckByte().toInt() shl 16
            val b4 = readAndCheckByte().toInt() shl 24

            return b4 or b3 or b2 or b1
        }

        /**
         * Reads a byte from the input stream checking that the end of file (EOF) has not been
         * encountered.
         *
         * @return byte read from input
         * @throws IOException if an error is encountered while reading
         * @throws EOFException if the end of file (EOF) is encountered.
         */
        private fun readAndCheckByte(): Byte {
            val b1 = input.read()

            if (-1 == b1) {
                throw EOFException()
            }

            return b1.toByte()
        }
    }
}
