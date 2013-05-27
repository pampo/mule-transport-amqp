/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.transport.amqp;

import java.io.IOException;

import org.apache.commons.lang.Validate;
import org.mule.api.MuleContext;
import org.mule.api.transaction.TransactionException;
import org.mule.config.i18n.CoreMessages;
import org.mule.transaction.AbstractSingleResourceTransaction;
import org.mule.transaction.IllegalTransactionStateException;

import com.rabbitmq.client.Channel;

/**
 * {@link AmqpTransaction} is a wrapper for an AMQP local transaction. This object
 * holds the AMQP channel and controls when the transaction is committed or rolled
 * back.
 */
public class AmqpTransaction extends AbstractSingleResourceTransaction
{
    public enum RecoverStrategy
    {
        NONE, NO_REQUEUE, REQUEUE
    };

    private final RecoverStrategy recoverStrategy;

    public AmqpTransaction(final MuleContext muleContext, final RecoverStrategy recoverStrategy)
    {
        super(muleContext);

        Validate.notNull(recoverStrategy, "recoverStrategy can't be null");
        this.recoverStrategy = recoverStrategy;
    }

    @Override
    public void bindResource(final Object key, final Object resource) throws TransactionException
    {
        if (!(resource instanceof Channel))
        {
            throw new IllegalTransactionStateException(
                CoreMessages.transactionCanOnlyBindToResources(Channel.class.getName()));
        }

        super.bindResource(key, resource);
    }

    @Override
    protected void doBegin() throws TransactionException
    {
        // NOOP
    }

    @Override
    protected void doCommit() throws TransactionException
    {
        if (resource == null)
        {
            logger.warn(CoreMessages.commitTxButNoResource(this));
            return;
        }

        try
        {
            ((Channel) resource).txCommit();
        }
        catch (final IOException ioe)
        {
            throw new TransactionException(CoreMessages.transactionCommitFailed(), ioe);
        }
    }

    @Override
    protected void doRollback() throws TransactionException
    {
        if (resource == null)
        {
            logger.warn(CoreMessages.rollbackTxButNoResource(this));
            return;
        }

        final Channel channel = (Channel) resource;

        try
        {
            channel.txRollback();
        }
        catch (final IOException ioe)
        {
            throw new TransactionException(CoreMessages.transactionRollbackFailed(), ioe);
        }

        try
        {
            channel.txRollback();
            switch (recoverStrategy)
            {
                case NONE :
                    // NOOP
                    break;
                case NO_REQUEUE :
                    channel.basicRecover(false);
                    break;
                case REQUEUE :
                    channel.basicRecover(true);
                    break;
            }
        }
        catch (final IOException ioe)
        {
            logger.warn("Failed to recover channel " + channel + " after rollback (recoverStrategy is "
                        + recoverStrategy + ")");
        }
    }
}