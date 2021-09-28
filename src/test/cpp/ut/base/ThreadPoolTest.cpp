#include "ThreadPoolImpl.h"
#include "absl/memory/memory.h"
#include "absl/synchronization/mutex.h"
#include "rocketmq/RocketMQ.h"
#include "gtest/gtest.h"
#include <atomic>
#include <functional>
#include <thread>

ROCKETMQ_NAMESPACE_BEGIN

class ThreadPoolTest : public testing::Test {
public:
  ThreadPoolTest() = default;

  void SetUp() override {
    pool_ = absl::make_unique<ThreadPoolImpl>(2);
    pool_->start();
    completed = false;
  }

  void TearDown() override {
    pool_->shutdown();
  }

protected:
  std::unique_ptr<ThreadPool> pool_;
  absl::Mutex mtx;
  absl::CondVar cv;
  bool completed{false};
};

TEST_F(ThreadPoolTest, testBasics) {

  auto task = [this](int cnt) {
    for (int i = 0; i < cnt; i++) {
      std::cout << std::this_thread::get_id() << ": It works" << std::endl;
    }
    {
      absl::MutexLock lk(&mtx);
      if (!completed) {
        completed = true;
        cv.SignalAll();
      }
    }
  };

  for (int i = 0; i < 3; i++) {
    pool_->submit(std::bind(task, 3));
  }

  {
    absl::MutexLock lk(&mtx);
    if (!completed) {
      cv.Wait(&mtx);
    }
  }
}

ROCKETMQ_NAMESPACE_END
