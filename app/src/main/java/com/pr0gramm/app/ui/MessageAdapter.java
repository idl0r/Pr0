package com.pr0gramm.app.ui;

import android.content.Context;
import android.support.annotation.Nullable;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.common.collect.Iterables;
import com.pr0gramm.app.Dagger;
import com.pr0gramm.app.api.pr0gramm.response.Message;
import com.pr0gramm.app.services.UserService;

import java.util.ArrayList;
import java.util.List;


/**
 */
public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {
    protected final List<Message> messages;
    private final Context context;
    private final MessageActionListener actionListener;
    private final int itemLayout;

    @Nullable
    private final String userName;

    public MessageAdapter(Context context, List<Message> messages, MessageActionListener actionListener, int layout) {
        this.context = context;
        this.actionListener = actionListener;
        this.messages = new ArrayList<>(messages);
        this.itemLayout = layout;

        UserService userService = Dagger.appComponent(context).userService();
        userName = userService.getName().orNull();

        setHasStableIds(true);
    }

    /**
     * Replace all the messages with the new messages from the given iterable.
     */
    public void setMessages(Iterable<Message> messages) {
        this.messages.clear();
        Iterables.addAll(this.messages, messages);
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        return messages.get(position).getId();
    }

    @Override
    public MessageViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(itemLayout, parent, false);
        return new MessageViewHolder(view);
    }

    @SuppressWarnings("CodeBlock2Expr")
    @Override
    public void onBindViewHolder(MessageViewHolder holder, int position) {
        Message message = messages.get(position);
        MessageView view = holder.view;
        view.update(message, userName);

        if (actionListener != null) {
            view.setOnSenderClickedListener(v -> {
                actionListener.onUserClicked(message.getSenderId(), message.getName());
            });

            boolean isComment = message.getItemId() != 0;
            if (isComment) {
                view.setAnswerClickedListener(v -> {
                    actionListener.onAnswerToCommentClicked(message);
                });

                view.setOnClickListener(v -> {
                    actionListener.onCommentClicked(message.getItemId(), message.getId());
                });
            } else {
                view.setAnswerClickedListener(v -> {
                    actionListener.onAnswerToPrivateMessage(message);
                });
                view.setOnClickListener(null);
            }
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    protected static class MessageViewHolder extends RecyclerView.ViewHolder {
        final MessageView view;

        public MessageViewHolder(View itemView) {
            super(itemView);
            view = (MessageView) itemView;
        }
    }
}
