package com.metrolist.innertube.models.response

import kotlinx.serialization.Serializable

@Serializable
data class CommentsResponse(
    val frameworkUpdates: FrameworkUpdates?,
) {
    @Serializable
    data class FrameworkUpdates(
        val entityBatchUpdate: EntityBatchUpdate?,
    ) {
        @Serializable
        data class EntityBatchUpdate(
            val mutations: List<Mutation>?,
        ) {
            @Serializable
            data class Mutation(
                val payload: Payload?,
            ) {
                @Serializable
                data class Payload(
                    val commentEntityPayload: Comment?,
                )
            }
        }
    }

    @Serializable
    data class Comment(
        val properties: Properties?,
        val author: Author?,
        val toolbar: Toolbar?,
    ) {
        @Serializable
        data class Properties(
            val content: Content?,
            val publishedTime: String?,
        ) {
            @Serializable
            data class Content(
                val content: String?,
            )
        }

        @Serializable
        data class Author(
            val displayName: String?,
            val avatarThumbnailUrl: String?,
        )

        @Serializable
        data class Toolbar(
            val likeCountNotliked: String?,
        )
    }
}
