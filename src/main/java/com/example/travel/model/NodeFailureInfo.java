package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;

public class NodeFailureInfo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String lastFailedNode = "";
    private String lastError = "";
    private String failureType = "";
    private boolean retryable = true;
    private int nodeRetryCount;

    public String getLastFailedNode() {
        return lastFailedNode;
    }

    public void setLastFailedNode(String lastFailedNode) {
        this.lastFailedNode = lastFailedNode == null ? "" : lastFailedNode;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError == null ? "" : lastError;
    }

    public String getFailureType() {
        return failureType;
    }

    public void setFailureType(String failureType) {
        this.failureType = failureType == null ? "" : failureType;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public void setRetryable(boolean retryable) {
        this.retryable = retryable;
    }

    public int getNodeRetryCount() {
        return nodeRetryCount;
    }

    public void setNodeRetryCount(int nodeRetryCount) {
        this.nodeRetryCount = Math.max(0, nodeRetryCount);
    }
}
