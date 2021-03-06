/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.client.kex;

import java.math.BigInteger;
import java.security.PublicKey;

import org.apache.sshd.client.session.ClientSessionImpl;
import org.apache.sshd.common.Digest;
import org.apache.sshd.common.KeyExchange;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.Signature;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.kex.AbstractDH;
import org.apache.sshd.common.kex.DH;
import org.apache.sshd.common.session.AbstractSession;
import org.apache.sshd.common.util.Buffer;
import org.apache.sshd.common.util.KeyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client side Diffie Hellman Group Exchange
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class DHGEX implements KeyExchange {

    /**
     * Named factory for DHGEX key exchange
     */
    public static class Factory implements NamedFactory<KeyExchange> {

        public String getName() {
            return "diffie-hellman-group-exchange-sha1";
        }

        public KeyExchange create() {
            return new DHGEX();
        }

    }

    private final Logger log = LoggerFactory.getLogger(getClass());

    private ClientSessionImpl session;
    private byte[] V_S;
    private byte[] V_C;
    private byte[] I_S;
    private byte[] I_C;
    private Digest hash;
    private AbstractDH dh;
    private byte[] p;
    private byte[] g;
    private byte[] e;
    private byte[] f;
    private byte[] K;
    private byte[] H;
    private PublicKey serverKey;
    private byte expected;

    int min = 1024;
    int prf = 4096;
    int max = 8192;

    public void init(AbstractSession s, byte[] V_S, byte[] V_C, byte[] I_S, byte[] I_C) throws Exception {
        if (!(s instanceof ClientSessionImpl)) {
            throw new IllegalStateException("Using a client side KeyExchange on a server");
        }
        session = (ClientSessionImpl) s;
        this.V_S = V_S;
        this.V_C = V_C;
        this.I_S = I_S;
        this.I_C = I_C;

        log.debug("Send SSH_MSG_KEX_DH_GEX_REQUEST");
        Buffer buffer = session.createBuffer(SshConstants.SSH_MSG_KEX_DH_GEX_REQUEST);
        buffer.putInt(min);
        buffer.putInt(prf);
        buffer.putInt(max);
        session.writePacket(buffer);

        expected = SshConstants.SSH_MSG_KEX_DH_GEX_GROUP;
    }

    public boolean next(Buffer buffer) throws Exception {
        byte cmd = buffer.getByte();
        if (cmd != expected) {
            throw new SshException(SshConstants.SSH2_DISCONNECT_KEY_EXCHANGE_FAILED,
                    "Protocol error: expected packet " + expected + ", got " + cmd);
        }

        if (cmd == SshConstants.SSH_MSG_KEX_DH_GEX_GROUP) {
            log.debug("Received SSH_MSG_KEX_DH_GEX_GROUP");
            p = buffer.getMPIntAsBytes();
            g = buffer.getMPIntAsBytes();

            dh = getDH(new BigInteger(p), new BigInteger(g));
            hash =  dh.getHash();
            hash.init();
            e = dh.getE();

            log.debug("Send SSH_MSG_KEX_DH_GEX_INIT");
            buffer = session.createBuffer(SshConstants.SSH_MSG_KEX_DH_GEX_INIT);
            buffer.putMPInt(e);
            session.writePacket(buffer);
            expected = SshConstants.SSH_MSG_KEX_DH_GEX_REPLY;
            return false;
        }

        if (cmd == SshConstants.SSH_MSG_KEX_DH_GEX_REPLY) {
            log.debug("Received SSH_MSG_KEX_DH_GEX_REPLY");
            byte[] K_S = buffer.getBytes();
            f = buffer.getMPIntAsBytes();
            byte[] sig = buffer.getBytes();
            dh.setF(f);
            K = dh.getK();

            buffer = new Buffer(K_S);
            serverKey = buffer.getRawPublicKey();
            final String keyAlg = KeyUtils.getKeyType(serverKey);
            if (keyAlg == null) {
                throw new SshException("Unsupported server key type");
            }

            buffer = new Buffer();
            buffer.putString(V_C);
            buffer.putString(V_S);
            buffer.putString(I_C);
            buffer.putString(I_S);
            buffer.putString(K_S);
            buffer.putInt(min);
            buffer.putInt(prf);
            buffer.putInt(max);
            buffer.putMPInt(p);
            buffer.putMPInt(g);
            buffer.putMPInt(e);
            buffer.putMPInt(f);
            buffer.putMPInt(K);
            hash.update(buffer.array(), 0, buffer.available());
            H = hash.digest();

            Signature verif = NamedFactory.Utils.create(session.getFactoryManager().getSignatureFactories(), keyAlg);
            verif.init(serverKey, null);
            verif.update(H, 0, H.length);
            if (!verif.verify(sig)) {
                throw new SshException(SshConstants.SSH2_DISCONNECT_KEY_EXCHANGE_FAILED,
                        "KeyExchange signature verification failed");
            }
            return true;
        }

        throw new IllegalStateException();
    }

    protected DH getDH(BigInteger p, BigInteger g) throws Exception {
        DH dh = new DH();
        dh.setP(p);
        dh.setG(g);
        return dh;
    }

    public Digest getHash() {
        return hash;
    }

    public byte[] getH() {
        return H;
    }

    public byte[] getK() {
        return K;
    }

    public PublicKey getServerKey() {
        return serverKey;
    }
}
